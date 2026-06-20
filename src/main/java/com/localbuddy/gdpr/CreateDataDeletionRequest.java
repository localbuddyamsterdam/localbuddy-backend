package com.localbuddy.gdpr;

import jakarta.validation.constraints.Size;

public record CreateDataDeletionRequest(
        @Size(max = 2000) String reason
) {
}
