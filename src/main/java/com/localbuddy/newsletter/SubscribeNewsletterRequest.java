package com.localbuddy.newsletter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public newsletter subscribe (anonymous). Logged-in users use the authenticated endpoint. */
public record SubscribeNewsletterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "A valid email is required")
        @Size(max = 255)
        String email,

        /** Optional self-declared segment; defaults to ALL when omitted. */
        NewsletterAudience audience,

        @Size(max = 80)
        String source
) {
}
