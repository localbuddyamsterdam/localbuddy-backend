package com.localbuddy.notification.email;

public record EmailSendRequest(
        String toEmail,
        String subject,
        String message,
        String htmlBody,
        String icsContent
) {
}