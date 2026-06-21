package com.localbuddy.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoicePdfServiceTest {

    @Test
    void rendersAValidPdf() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("LB-2026-0001");
        invoice.setInvoiceType(InvoiceType.COMMISSION);
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setIssuerName("LocalBuddy B.V.");
        invoice.setIssuerVatNumber("NL123456789B01");
        invoice.setIssuerAddress("Damrak 1\n1012 Amsterdam");
        invoice.setRecipientType(RecipientType.HOST);
        invoice.setRecipientName("Jane Host");
        invoice.setCurrency("EUR");
        invoice.setSubtotalAmount(new BigDecimal("20.00"));
        invoice.setVatAmount(new BigDecimal("4.20"));
        invoice.setTotalAmount(new BigDecimal("24.20"));
        invoice.setVatNote("VAT charged; recipient is VAT-registered.");
        invoice.setIssuedAt(Instant.parse("2026-06-21T00:00:00Z"));

        InvoiceLine line = new InvoiceLine();
        line.setDescription("Platform commission for booking LB-ABC123");
        line.setNetAmount(new BigDecimal("20.00"));
        line.setVatRate(new BigDecimal("0.2100"));
        line.setVatAmount(new BigDecimal("4.20"));
        line.setTotalAmount(new BigDecimal("24.20"));

        byte[] pdf = new InvoicePdfService().render(invoice, List.of(line));

        assertTrue(pdf.length > 500, "PDF should have content");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "should be a PDF document");
    }
}
