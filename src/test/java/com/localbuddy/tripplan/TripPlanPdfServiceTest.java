package com.localbuddy.tripplan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders a realistic AI-flavoured plan (em dashes, curly quotes, accents, emoji, nulls)
 * to catch openhtmltopdf parse/encoding failures that only surface with live model output.
 */
class TripPlanPdfServiceTest {

    private final TripPlanPdfService service = new TripPlanPdfService();

    @Test
    void rendersRealisticPlanWithUnicodeContent() {
        TripPlanItem experience = new TripPlanItem(
                "item-1", "10:00", "EXPERIENCE",
                "Café crawl through De Pijp — hidden gems & local favourites",
                "You'll taste stroopwafels, poffertjes & more. Don't miss the 'brown café' stop!",
                UUID.randomUUID(), "cafe-crawl", "Café crawl", "https://example.com/e/cafe-crawl",
                "https://example.com/book", UUID.randomUUID(), Instant.now(), Instant.now(),
                new BigDecimal("49.50"), "De Pijp, Amsterdam", "https://maps.example.com", true, true, null, null);
        TripPlanItem food = new TripPlanItem(
                "item-2", "13:30", "FOOD",
                "Lunch at Foodhallen 🍜",
                "Indoor food market — grab bites from a dozen stalls. <script>not-html</script>",
                null, null, null, null, null, null, null, null, null,
                "Foodhallen", "https://maps.example.com/foodhallen", false, null, null, null);
        TripPlanItem tip = new TripPlanItem(
                "item-3", null, "TIP",
                "Rent a bike",
                "It's the fastest way around — but watch the tram tracks!\nSecond line after newline.",
                null, null, null, null, null, null, null, null, null, null, null, false, null, null, null);

        TripPlanDay day = new TripPlanDay(LocalDate.of(2026, 8, 1), "Neighbourhood flavours — south of the canals", List.of(experience, food, tip));
        TripPlanDocument doc = new TripPlanDocument(
                "3 days in Amsterdam — food, canals & culture",
                "A relaxed pace with the city's best bites… and a few surprises.",
                "EUR", new BigDecimal("199.00"), List.of(day),
                List.of("Trams stop running around midnight — check GVB times.", "Tap water is safe & free."));

        TripPlanResponse plan = new TripPlanResponse(
                "tok-123", "https://example.com/trip-planner/tok-123", "https://example.com/trip-planner/tok-123#book",
                "amsterdam", "Amsterdam", "Netherlands",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3), 2, false,
                "food, history", "en", doc, List.of("item-1"), Instant.now(), false, false, null);

        byte[] pdf = service.render(plan);
        assertNotNull(pdf);
        assertTrue(pdf.length > 500, "PDF should be non-trivial, got " + pdf.length + " bytes");
    }

    @Test
    void rendersPlanWithNullPartySizeAndEmptyDays() {
        TripPlanDocument doc = new TripPlanDocument("Quick escape", null, "EUR", null, List.of(), List.of());
        TripPlanResponse plan = new TripPlanResponse(
                "tok-456", "https://example.com/trip-planner/tok-456", "https://example.com/trip-planner/tok-456#book",
                "paris", "Paris", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), null, true,
                null, "en", doc, List.of(), Instant.now(), false, false, null);

        byte[] pdf = service.render(plan);
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
    }
}
