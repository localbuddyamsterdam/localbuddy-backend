package com.localbuddy.gdpr;

import jakarta.validation.constraints.Size;

public record ProcessDataDeletionRequest(
        @Size(max = 2000) String adminNote
) {
}
