package com.localbuddy.notification;

import com.localbuddy.notification.email.EmailProviderService;
import com.localbuddy.notification.email.EmailSendRequest;
import com.localbuddy.notification.email.EmailSendResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/email")
@Tag(name = "Admin - Email", description = "Email diagnostics for verifying delivery configuration")
@SecurityRequirement(name = "bearerAuth")
public class AdminEmailController {

    private final EmailProviderService emailProviderService;

    public AdminEmailController(EmailProviderService emailProviderService) {
        this.emailProviderService = emailProviderService;
    }

    @Operation(
            summary = "Send a test email",
            description = "Sends a test email through the ACTIVE email provider: with EMAIL_PROVIDER=console it is "
                    + "logged only; with EMAIL_PROVIDER=azure it is actually delivered via Azure Communication "
                    + "Services. The response reports success + the provider message id, or the failure reason "
                    + "(e.g. a missing/invalid connection string). Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Send attempted — see success/providerMessageId/failureReason in the body"),
            @ApiResponse(responseCode = "400", description = "Invalid request (bad email)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @PostMapping("/test")
    public ResponseEntity<EmailSendResult> sendTestEmail(@Valid @RequestBody TestEmailRequest request) {
        String subject = (request.subject() == null || request.subject().isBlank())
                ? "LocalBuddy test email" : request.subject();
        String message = (request.message() == null || request.message().isBlank())
                ? "This is a test email from LocalBuddy confirming that email delivery is configured correctly."
                : request.message();

        EmailSendResult result = emailProviderService.sendEmail(
                new EmailSendRequest(request.to().trim(), subject, message, null, null));
        return ResponseEntity.ok(result);
    }

    public record TestEmailRequest(
            @NotBlank(message = "Recipient email is required")
            @Email(message = "Recipient must be a valid email")
            String to,
            String subject,
            String message
    ) {
    }
}
