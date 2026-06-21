package com.localbuddy.invoice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertCompanySettingsRequest(
        @NotBlank @Size(max = 200) String legalName,
        @Size(max = 200) String tradingName,
        @Size(max = 40) String vatNumber,
        @Size(max = 40) String cocNumber,
        @Size(max = 200) String addressLine1,
        @Size(max = 200) String addressLine2,
        @Size(max = 20) String postalCode,
        @Size(max = 100) String city,
        @Size(max = 2) String country,
        @Size(max = 255) String email,
        @Size(max = 40) String phone,
        @Size(max = 64) String iban,
        @Size(max = 20) String invoiceNumberPrefix,
        String invoiceFooter
) {
}
