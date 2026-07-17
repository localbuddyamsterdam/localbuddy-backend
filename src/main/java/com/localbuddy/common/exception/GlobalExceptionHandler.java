package com.localbuddy.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Friendly, user-facing messages for the DB constraints most likely to be hit by input,
     * keyed by the DB constraint name. Anything not listed still gets a correct status (409/400)
     * with a safe generic message. Status is inferred from the name prefix (chk_ → 400, else 409).
     */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("ux_bookings_active_guest_slot", "You already have a booking for this time slot."),
            Map.entry("ux_bookings_active_traveler_slot", "You already have a booking for this time slot."),
            Map.entry("ux_waitlist_active_guest_slot", "You're already on the waitlist for this slot."),
            Map.entry("ux_waitlist_active_user_slot", "You're already on the waitlist for this slot."),
            Map.entry("ux_payments_booking_active", "A payment is already being processed for this booking."),
            Map.entry("uk_newsletter_email", "This email is already subscribed to our newsletter."),
            Map.entry("uk_host_follow", "You're already following this host."),
            Map.entry("uk_wishlist_user_experience", "This experience is already in your wishlist."),
            Map.entry("users_email_key", "An account with this email already exists."),
            Map.entry("experiences_slug_key", "An experience with this name already exists."),
            Map.entry("chk_bookings_traveler_or_guest", "A booking needs either a signed-in traveler or full guest details (name, email, and phone)."),
            Map.entry("chk_waitlist_user_or_guest", "A waitlist entry needs either a signed-in user or full guest details."),
            Map.entry("chk_reviews_rating_range", "Rating must be between 1 and 5 stars."),
            Map.entry("chk_availability_time_valid", "The end time must be after the start time."),
            Map.entry("chk_bookings_guests_count_positive", "Number of guests must be at least 1."),
            Map.entry("chk_experiences_duration_positive", "Experience duration must be greater than 0 minutes."),
            Map.entry("chk_experiences_price_non_negative", "Experience price can't be negative."),
            Map.entry("chk_experiences_max_guests_positive", "Maximum guests must be at least 1."),
            Map.entry("chk_availability_capacity_positive", "Slot capacity must be greater than 0.")
    );

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            BadRequestException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            ConflictException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining(", "));

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid request body",
                request
        );
    }

    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleNotFound(
            Exception ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Endpoint not found",
                request
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file is too large",
                request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'",
                request
        );
    }

    /**
     * DB-level integrity violations (unique / check / foreign-key / not-null). Without this they
     * fall through to the catch-all below and surface as a 500. Map them to a proper status with a
     * safe, generic message — the real cause is logged server-side and never leaked to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        String constraint = extractConstraintName(ex);
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null ? root.getMessage() : ex.getMessage();
        log.warn("Data integrity violation on {} {} (constraint={}): {}",
                request.getMethod(), request.getRequestURI(), constraint, detail);

        HttpStatus status = statusForConstraint(constraint, detail);
        String mapped = constraint != null ? CONSTRAINT_MESSAGES.get(constraint) : null;
        String message = mapped != null ? mapped : defaultIntegrityMessage(status);
        return buildErrorResponse(status, message, request);
    }

    /** Walks the cause chain for Hibernate's constraint name (best-effort; null if unavailable). */
    private String extractConstraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException hce) {
                return hce.getConstraintName();
            }
        }
        return null;
    }

    private HttpStatus statusForConstraint(String constraint, String detail) {
        if (constraint != null) {
            if (constraint.startsWith("chk_")) {
                return HttpStatus.BAD_REQUEST;
            }
            if (constraint.startsWith("ux_") || constraint.startsWith("uq_")
                    || constraint.startsWith("uk_") || constraint.endsWith("_key")) {
                return HttpStatus.CONFLICT;
            }
        }
        String lower = detail == null ? "" : detail.toLowerCase();
        if (lower.contains("duplicate key") || lower.contains("unique constraint")
                || lower.contains("already exists")) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private String defaultIntegrityMessage(HttpStatus status) {
        return status == HttpStatus.CONFLICT
                ? "This conflicts with a record that already exists."
                : "The request couldn't be completed because some information is missing or invalid.";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        // A bad argument reaching a service (e.g. a malformed id parsed with UUID.fromString) is a
        // client error, not a 500. Logged at WARN so genuine bugs stay visible; client gets a safe message.
        log.warn("Illegal argument on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "The request contained an invalid value.",
                request
        );
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(
            ServiceUnavailableException ex,
            HttpServletRequest request
    ) {
        log.warn("Upstream unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(
            UnauthorizedException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
            ForbiddenException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
            RateLimitExceededException ex,
            HttpServletRequest request
    ) {
        // Rate limit exceeded → 429 (was surfacing as a 400 via BadRequestException before).
        return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(response);
    }
}