package com.localbuddy.safety;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class BookingSafetyChecklistService {

    private final BookingSafetyChecklistRepository checklistRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public BookingSafetyChecklistService(BookingSafetyChecklistRepository checklistRepository,
                                         BookingRepository bookingRepository,
                                         UserRepository userRepository) {
        this.checklistRepository = checklistRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public BookingSafetyChecklistResponse getMyChecklist(UUID userId, UUID bookingId) {
        Booking booking = getBooking(bookingId);
        BookingSafetyRoleContext roleContext = resolveRoleContext(userId, booking);

        return checklistRepository.findByBookingIdAndUserId(bookingId, userId)
                .map(this::toResponse)
                .orElseGet(() -> new BookingSafetyChecklistResponse(
                        null,
                        bookingId,
                        userId,
                        roleContext,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null
                ));
    }

    @Transactional
    public BookingSafetyChecklistResponse completeMyChecklist(
            UUID userId,
            UUID bookingId,
            CompleteBookingSafetyChecklistRequest request,
            String ipAddress,
            String userAgent
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Booking booking = getBooking(bookingId);
        BookingSafetyRoleContext roleContext = resolveRoleContext(userId, booking);

        if (booking.getStatus() != BookingStatus.CONFIRMED &&
                booking.getStatus() != BookingStatus.ACCEPTED) {
            throw new BadRequestException("Safety checklist can only be completed for accepted or confirmed bookings");
        }

        BookingSafetyChecklist checklist = checklistRepository
                .findByBookingIdAndUserId(bookingId, userId)
                .orElseGet(() -> {
                    BookingSafetyChecklist newChecklist = new BookingSafetyChecklist();
                    newChecklist.setBooking(booking);
                    newChecklist.setUser(user);
                    newChecklist.setRoleContext(roleContext);
                    return newChecklist;
                });

        checklist.setPublicMeetingAcknowledged(Boolean.TRUE.equals(request.publicMeetingAcknowledged()));
        checklist.setCommunicationGuidelinesAcknowledged(Boolean.TRUE.equals(request.communicationGuidelinesAcknowledged()));
        checklist.setPersonalSafetyAcknowledged(Boolean.TRUE.equals(request.personalSafetyAcknowledged()));
        checklist.setReportingGuidelinesAcknowledged(Boolean.TRUE.equals(request.reportingGuidelinesAcknowledged()));
        checklist.setCompleted(true);
        checklist.setCompletedAt(Instant.now());
        checklist.setIpAddress(optionalTrim(ipAddress));
        checklist.setUserAgent(optionalTrim(userAgent));

        return toResponse(checklistRepository.save(checklist));
    }

    private Booking getBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    private BookingSafetyRoleContext resolveRoleContext(UUID userId, Booking booking) {
        if (booking.getLoggedInUser() != null &&
                booking.getLoggedInUser().getId().equals(userId)) {
            return BookingSafetyRoleContext.TRAVELER;
        }

        if (booking.getLocalProfile() != null &&
                booking.getLocalProfile().getUser() != null &&
                booking.getLocalProfile().getUser().getId().equals(userId)) {
            return BookingSafetyRoleContext.LOCAL;
        }

        throw new BadRequestException("You do not have access to this booking");
    }

    private BookingSafetyChecklistResponse toResponse(BookingSafetyChecklist checklist) {
        return new BookingSafetyChecklistResponse(
                checklist.getId(),
                checklist.getBooking().getId(),
                checklist.getUser().getId(),
                checklist.getRoleContext(),
                checklist.isPublicMeetingAcknowledged(),
                checklist.isCommunicationGuidelinesAcknowledged(),
                checklist.isPersonalSafetyAcknowledged(),
                checklist.isReportingGuidelinesAcknowledged(),
                checklist.isCompleted(),
                checklist.getCompletedAt(),
                checklist.getCreatedAt(),
                checklist.getUpdatedAt()
        );
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}