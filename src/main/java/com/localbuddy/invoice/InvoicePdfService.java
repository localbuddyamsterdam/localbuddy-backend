package com.localbuddy.invoice;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders an invoice (data + lines) to a PDF document via openhtmltopdf. */
@Service
public class InvoicePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    public byte[] render(Invoice invoice, List<InvoiceLine> lines) {
        String html = buildHtml(invoice, lines);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render invoice PDF: " + ex.getMessage(), ex);
        }
    }

    private String buildHtml(Invoice inv, List<InvoiceLine> lines) {
        StringBuilder rows = new StringBuilder();
        for (InvoiceLine line : lines) {
            rows.append("<tr>")
                    .append("<td>").append(esc(line.getDescription())).append("</td>")
                    .append("<td class='r'>").append(money(line.getNetAmount())).append("</td>")
                    .append("<td class='r'>").append(pct(line.getVatRate())).append("</td>")
                    .append("<td class='r'>").append(money(line.getVatAmount())).append("</td>")
                    .append("<td class='r'>").append(money(line.getTotalAmount())).append("</td>")
                    .append("</tr>");
        }

        String docTitle = switch (inv.getInvoiceType()) {
            case SERVICE_FEE_RECEIPT -> "Receipt";
            case PAYOUT_STATEMENT -> "Payout statement";
            default -> "Invoice";
        };

        return "<!DOCTYPE html><html><head><meta charset='utf-8'/><style>"
                + "body{font-family:Helvetica,Arial,sans-serif;font-size:11px;color:#222;}"
                + "h1{font-size:20px;margin:0 0 2px 0;}"
                + ".muted{color:#666;}"
                + ".box{width:100%;margin-bottom:18px;}"
                + ".left{float:left;width:50%;}.right{float:right;width:50%;text-align:right;}"
                + ".clear{clear:both;}"
                + "table{width:100%;border-collapse:collapse;margin-top:8px;}"
                + "th,td{padding:6px 8px;border-bottom:1px solid #e2e2e2;}"
                + "th{text-align:left;background:#f5f5f5;}"
                + ".r{text-align:right;}"
                + ".totals{margin-top:10px;width:40%;float:right;}"
                + ".totals td{border:none;padding:3px 8px;}"
                + ".grand{font-weight:bold;border-top:2px solid #333;}"
                + ".note{clear:both;margin-top:30px;font-size:10px;color:#555;}"
                + "</style></head><body>"
                + "<div class='box'>"
                + "<div class='left'><h1>" + esc(orEmpty(inv.getIssuerName())) + "</h1>"
                + "<div class='muted'>" + esc(orEmpty(inv.getIssuerAddress())) + "</div>"
                + (notBlank(inv.getIssuerVatNumber()) ? "<div class='muted'>VAT: " + esc(inv.getIssuerVatNumber()) + "</div>" : "")
                + "</div>"
                + "<div class='right'><h1>" + docTitle + "</h1>"
                + "<div>" + esc(orEmpty(inv.getInvoiceNumber())) + "</div>"
                + "<div class='muted'>" + (inv.getIssuedAt() != null ? DATE.format(inv.getIssuedAt()) : "") + "</div>"
                + "</div><div class='clear'></div></div>"
                + "<div class='box'><strong>Billed to</strong><br/>"
                + esc(orEmpty(inv.getRecipientName())) + "<br/>"
                + (notBlank(inv.getRecipientAddress()) ? esc(inv.getRecipientAddress()) + "<br/>" : "")
                + (notBlank(inv.getRecipientVatNumber()) ? "VAT: " + esc(inv.getRecipientVatNumber()) + "<br/>" : "")
                + (notBlank(inv.getRecipientEmail()) ? esc(inv.getRecipientEmail()) : "")
                + "</div>"
                + "<table><thead><tr><th>Description</th><th class='r'>Net</th><th class='r'>VAT %</th>"
                + "<th class='r'>VAT</th><th class='r'>Total</th></tr></thead><tbody>"
                + rows + "</tbody></table>"
                + "<table class='totals'>"
                + "<tr><td>Subtotal (net)</td><td class='r'>" + money(inv.getSubtotalAmount()) + "</td></tr>"
                + "<tr><td>VAT</td><td class='r'>" + money(inv.getVatAmount()) + "</td></tr>"
                + "<tr class='grand'><td>Total " + esc(orEmpty(inv.getCurrency())) + "</td><td class='r'>"
                + money(inv.getTotalAmount()) + "</td></tr>"
                + "</table><div class='clear'></div>"
                + (notBlank(inv.getVatNote()) ? "<div class='note'>" + esc(inv.getVatNote()) + "</div>" : "")
                + "</body></html>";
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal rate) {
        if (rate == null || rate.signum() == 0) {
            return "—";
        }
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>");
    }
}
