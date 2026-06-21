package com.localbuddy.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendWhatsAppRequest(
        @NotBlank @Size(max = 40) String toPhone,
        @NotBlank @Size(max = 4000) String message
) {
}
