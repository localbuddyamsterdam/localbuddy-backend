package com.localbuddy.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        String invoiceNumber,
        InvoiceType invoiceType,
        InvoiceStatus status,
        RecipientType recipientType,
        String recipientName,
        String currency,
        BigDecimal subtotalAmount,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        String vatNote,
        UUID bookingId,
        UUID payoutId,
        Instant issuedAt
) {
}
