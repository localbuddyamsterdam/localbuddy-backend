package com.localbuddy.tripplan;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders a saved trip plan (itinerary) to a PDF document via openhtmltopdf. */
@Service
public class TripPlanPdfService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);

    /**
     * The handful of static labels the PDF prints around the (already localized) plan text,
     * in the plan's own language. Weekday/month names follow the same locale via {@code locale}.
     */
    private record Labels(Locale locale, String day, String to, String travellerOne, String travellerMany,
                          String privateTours, String goodToKnow, String perPerson, String forYourGroup) {

        static Labels of(String language) {
            return switch (language == null ? "en" : language) {
                case "nl" -> new Labels(Locale.of("nl"), "Dag", "t/m", "reiziger", "reizigers",
                        "Privétours", "Goed om te weten", " p.p.", " voor je groep");
                case "fr" -> new Labels(Locale.of("fr"), "Jour", "au", "voyageur", "voyageurs",
                        "Visites privées", "Bon à savoir", " / personne", " pour votre groupe");
                default -> new Labels(Locale.ENGLISH, "Day", "to", "traveller", "travellers",
                        "Private tours", "Good to know", " / person", " for your group");
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TripPlanPdfService.class);

    /**
     * Renders a throwaway one-page document once after startup, off the request path, so the
     * first traveler's export doesn't pay the one-time JVM/renderer warm-up (class loading,
     * PDFBox font init) that only recurs after a restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread warmup = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                render(new TripPlanResponse(
                        "warmup", "", "", "warmup", "Warm-up", null,
                        LocalDate.now(), LocalDate.now(), 1, false, null, "en",
                        new TripPlanDocument("Warm-up", null, "EUR", null, List.of(), List.of()),
                        List.of(), Instant.now()));
                log.info("PDF renderer warmed up in {} ms", System.currentTimeMillis() - start);
            } catch (Exception ex) {
                // Warm-up is best-effort — a failure here must never affect startup.
                log.warn("PDF renderer warm-up failed: {}", ex.getMessage());
            }
        }, "pdf-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    public byte[] render(TripPlanResponse plan) {
        String html = buildHtml(plan);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render trip-plan PDF: " + ex.getMessage(), ex);
        }
    }

    private String buildHtml(TripPlanResponse plan) {
        TripPlanDocument doc = plan.plan();
        Labels labels = Labels.of(plan.language());
        DateTimeFormatter dayFormat = DAY_FORMAT.withLocale(labels.locale());
        StringBuilder days = new StringBuilder();
        int dayNum = 1;
        for (TripPlanDay day : doc.days()) {
            days.append("<div class='day'>")
                    .append("<div class='day-head'><span class='daynum'>").append(labels.day())
                    .append(" ").append(dayNum++).append("</span>")
                    .append("<span class='daydate'>").append(dayFormat.format(day.date())).append("</span></div>");
            if (notBlank(day.theme())) {
                days.append("<div class='theme'>").append(esc(day.theme())).append("</div>");
            }
            for (TripPlanItem item : day.items()) {
                days.append("<div class='item'>")
                        .append("<div class='time'>").append(esc(orEmpty(item.startTimeLocal()))).append("</div>")
                        .append("<div class='body'>")
                        .append("<div class='item-title'>").append(esc(item.title()));
                if ("EXPERIENCE".equals(item.kind()) && item.pricePerGuest() != null) {
                    days.append(" <span class='price'>").append(money(item.pricePerGuest()))
                            .append(" ").append(esc(doc.currency()))
                            // Private plans store the flat whole-group buyout price on the item.
                            .append(esc(plan.privateTour() ? labels.forYourGroup() : labels.perPerson()))
                            .append("</span>");
                }
                days.append("</div>");
                if (notBlank(item.description())) {
                    days.append("<div class='desc'>").append(esc(item.description())).append("</div>");
                }
                if (notBlank(item.placeName())) {
                    days.append("<div class='place'>").append(esc(item.placeName())).append("</div>");
                }
                days.append("</div></div>");
            }
            days.append("</div>");
        }

        StringBuilder tips = new StringBuilder();
        if (!doc.tips().isEmpty()) {
            tips.append("<div class='tips'><h2>").append(esc(labels.goodToKnow())).append("</h2><ul>");
            for (String tip : doc.tips()) {
                tips.append("<li>").append(esc(tip)).append("</li>");
            }
            tips.append("</ul></div>");
        }

        return "<!DOCTYPE html><html><head><meta charset='utf-8'/><style>"
                + "body{font-family:Helvetica,Arial,sans-serif;font-size:11px;color:#222;}"
                + "h1{font-size:22px;margin:0 0 4px 0;}"
                + "h2{font-size:14px;margin:0 0 8px 0;}"
                + ".muted{color:#666;}"
                + ".head{margin-bottom:20px;border-bottom:2px solid #d62f2a;padding-bottom:10px;}"
                + ".meta{color:#555;margin-top:4px;}"
                + ".summary{margin-top:8px;color:#333;}"
                + ".day{margin-top:16px;page-break-inside:avoid;}"
                + ".day-head{border-bottom:1px solid #ccc;padding-bottom:4px;margin-bottom:8px;}"
                + ".daynum{font-weight:bold;color:#d62f2a;margin-right:10px;}"
                + ".daydate{color:#555;}"
                + ".theme{font-style:italic;color:#444;margin-bottom:8px;}"
                + ".item{margin-bottom:8px;}"
                + ".time{display:inline-block;width:50px;color:#888;font-variant-numeric:tabular-nums;vertical-align:top;}"
                + ".body{display:inline-block;width:480px;}"
                + ".item-title{font-weight:bold;}"
                + ".price{font-weight:normal;color:#555;}"
                + ".desc{color:#444;margin-top:2px;}"
                + ".place{color:#777;margin-top:2px;}"
                + ".tips{margin-top:20px;border-top:1px solid #ccc;padding-top:10px;}"
                + ".tips ul{margin:0;padding-left:18px;}"
                + ".tips li{margin-bottom:4px;}"
                + "</style></head><body>"
                + "<div class='head'>"
                + "<h1>" + esc(doc.title()) + "</h1>"
                + "<div class='meta'>" + esc(plan.cityName())
                + (notBlank(plan.country()) ? ", " + esc(plan.country()) : "")
                // openhtmltopdf parses this as strict XML — named HTML entities like &middot; are
                // undeclared there and abort the whole render, so use the numeric form.
                + " &#183; " + plan.startDate() + " " + esc(labels.to()) + " " + plan.endDate()
                + partySizeFragment(plan.partySize(), labels)
                + (plan.privateTour() ? " &#183; " + esc(labels.privateTours()) : "")
                + "</div>"
                + (notBlank(doc.summary()) ? "<div class='summary'>" + esc(doc.summary()) + "</div>" : "")
                + "</div>"
                + days
                + tips
                + "</body></html>";
    }

    /** Older plans may have no stored party size — omit the fragment rather than print "null travellers". */
    private static String partySizeFragment(Integer partySize, Labels labels) {
        if (partySize == null) {
            return "";
        }
        return " &#183; " + partySize + " "
                + esc(partySize == 1 ? labels.travellerOne() : labels.travellerMany());
    }

    private static String money(BigDecimal v) {
        return v == null ? "0" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
