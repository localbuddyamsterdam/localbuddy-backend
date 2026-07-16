package com.localbuddy.invoice;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@Tag(name = "Invoices", description = "Authenticated host/customer invoices and receipts")
@SecurityRequirement(name = "bearerAuth")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/me")
    public ResponseEntity<List<InvoiceResponse>> getMyInvoices(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(invoiceService.getMyInvoices(userId));
    }

    @GetMapping("/{bookingId}/pdf")
    public ResponseEntity<byte[]> downloadPdf(Authentication authentication, @PathVariable UUID bookingId) {
        UUID userId = UUID.fromString(authentication.getName());
        InvoiceService.InvoicePdf invoice = invoiceService.renderPdfByBookingId(bookingId, userId, false);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + invoice.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(invoice.pdf());
    }
}
