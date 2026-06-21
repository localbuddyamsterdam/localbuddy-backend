package com.localbuddy.invoice;

import java.util.UUID;

public record CompanySettingsResponse(
        UUID id,
        String legalName,
        String tradingName,
        String vatNumber,
        String cocNumber,
        String addressLine1,
        String addressLine2,
        String postalCode,
        String city,
        String country,
        String email,
        String phone,
        String iban,
        String invoiceNumberPrefix,
        String invoiceFooter
) {
}
