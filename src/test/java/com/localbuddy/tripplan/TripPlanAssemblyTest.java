package com.localbuddy.tripplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.ai.AiStructuredResult;
import com.localbuddy.ai.ClaudeClient;
import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilitySlotService;
import com.localbuddy.availability.BookingWindowPolicy;
import com.localbuddy.deals.DealService;
import com.localbuddy.experience.City;
import com.localbuddy.experience.CityRepository;
import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the trip-planner's server-side validation of model output: every EXPERIENCE the
 * model references must map to real inventory — hallucinated experiences are dropped,
 * wrong/duplicate slots are repaired to a real free slot on the same day (or demoted to a
 * non-bookable recommendation), and the bookable deep links/prices come from the database,
 * never from the model. This is the guarantee that the planner cannot sell fiction.
 */
class TripPlanAssemblyTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate START = LocalDate.now(ZONE).plusDays(7);
    private static final UUID TRAVELER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final TripPlanRepository tripPlanRepository = mock(TripPlanRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CityRepository cityRepository = mock(CityRepository.class);
    private final AvailabilitySlotService availabilitySlotService = mock(AvailabilitySlotService.class);
    private final AvailabilitySlotRepository availabilitySlotRepository = mock(AvailabilitySlotRepository.class);
    private final BookingWindowPolicy bookingWindowPolicy = mock(BookingWindowPolicy.class);
    private final DealService dealService = mock(DealService.class);
    private final ClaudeClient claudeClient = mock(ClaudeClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final TripPlanService tripPlanService = new TripPlanService(
            tripPlanRepository, userRepository, cityRepository, availabilitySlotService, availabilitySlotRepository,
            bookingWindowPolicy, dealService, claudeClient, objectMapper,
            "https://app.example.com", 7, 40, 4, 12000);

    private final City city = city();
    private final Experience experience = experience(city);
    private final AvailabilitySlot morningSlot = slot(experience, START, 10, 0);
    private final AvailabilitySlot afternoonSlot = slot(experience, START, 14, 0);

    private City city() {
        City c = new City();
        c.setName("Amsterdam");
        c.setSlug("amsterdam");
        c.setCountry("Netherlands");
        c.setTimezone("Europe/Amsterdam");
        c.setActive(true);
        return c;
    }

    private Experience experience(City c) {
        LocalProfile host = new LocalProfile();
        host.setRatingAvg(new BigDecimal("4.90"));
        host.setTotalReviews(20);

        Experience e = new Experience();
        e.setTitle("Canal food walk");
        e.setSlug("canal-food-walk");
        e.setDescription("Eat your way along the canals with a local.");
        e.setDurationMinutes(120);
        e.setPriceAmount(new BigDecimal("45.00"));
        e.setCurrency("EUR");
        e.setCity(c);
        e.setLocalProfile(host);
        setId(e);
        return e;
    }

    private void setId(Experience e) {
        try {
            var field = Experience.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(e, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AvailabilitySlot slot(Experience e, LocalDate date, int hour, int minute) {
        AvailabilitySlot slot = new AvailabilitySlot();
        slot.setId(UUID.randomUUID());
        slot.setExperience(e);
        slot.setStartTime(ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), ZONE).toInstant());
        slot.setEndTime(ZonedDateTime.of(date, java.time.LocalTime.of(hour + 2, minute), ZONE).toInstant());
        slot.setCapacity(8);
        slot.setBookedCount(0);
        return slot;
    }

    private void stubWorld(String modelJson) throws Exception {
        when(cityRepository.findBySlug("amsterdam")).thenReturn(Optional.of(city));
        when(availabilitySlotService.getBookableSlotsForCityBetween(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(morningSlot, afternoonSlot));
        when(dealService.listLiveDeals(any(), any(), any(), any())).thenReturn(List.of());
        when(claudeClient.isConfigured()).thenReturn(true);
        when(claudeClient.getModel()).thenReturn("claude-sonnet-5");
        when(claudeClient.completeStructured(anyString(), any(), any(), any()))
                .thenReturn(new AiStructuredResult(objectMapper.readTree(modelJson), 100, 200, "end_turn"));
        when(tripPlanRepository.save(any(TripPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private String modelJson(String itemsJson) {
        return """
                {"title":"A day in Amsterdam","summary":"Great day.","days":[
                  {"date":"%s","theme":"Canals","items":[%s]}
                ],"tips":["Bring a raincoat"]}
                """.formatted(START, itemsJson);
    }

    private CreateTripPlanRequest request() {
        return new CreateTripPlanRequest("amsterdam", START, START, 2, "food", null, null, null);
    }

    @Test
    @DisplayName("a valid experience+slot reference becomes a bookable item with DB-derived time, price, and deep link")
    void validReferenceBecomesBookable() throws Exception {
        stubWorld(modelJson("""
                {"startTimeLocal":"10:00","kind":"EXPERIENCE","title":"Canal food walk",
                 "description":"Snack along the canals.","experienceId":"%s","slotId":"%s",
                 "placeName":null,"placeArea":null}
                """.formatted(experience.getId(), morningSlot.getId())));

        TripPlanResponse response = tripPlanService.createPlan(request(), TRAVELER_ID);

        TripPlanItem item = response.plan().days().get(0).items().get(0);
        assertTrue(item.bookable(), "item is bookable");
        assertEquals(morningSlot.getId(), item.slotId());
        assertEquals("10:00", item.startTimeLocal(), "time comes from the real slot");
        assertEquals(new BigDecimal("45.00"), item.pricePerGuest(), "price comes from the DB");
        assertTrue(item.bookingUrl().contains("/experience/" + experience.getId() + "/book"), "deep link to checkout");
        assertTrue(item.bookingUrl().contains("guests=2"), "party size prefilled");
        assertEquals(new BigDecimal("90.00"), response.plan().estimatedBookableTotal(),
                "estimate = price x party size");
    }

    @Test
    @DisplayName("a private-tour plan uses buyout inventory, prices items at the flat group price, and deep-links private checkout")
    void privateTourUsesFlatGroupPricing() throws Exception {
        experience.setPrivatePrice(new BigDecimal("300.00"));
        stubWorld(modelJson("""
                {"startTimeLocal":"10:00","kind":"EXPERIENCE","title":"Canal food walk",
                 "description":"Just your group.","experienceId":"%s","slotId":"%s",
                 "placeName":null,"placeArea":null}
                """.formatted(experience.getId(), morningSlot.getId())));
        when(availabilitySlotService.getPrivateBuyoutSlotsForCityBetween(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(morningSlot, afternoonSlot));

        CreateTripPlanRequest privateRequest =
                new CreateTripPlanRequest("amsterdam", START, START, 2, "food", null, true, null);
        TripPlanResponse response = tripPlanService.createPlan(privateRequest, TRAVELER_ID);

        assertTrue(response.privateTour(), "response carries the private-tour flag");
        TripPlanItem item = response.plan().days().get(0).items().get(0);
        assertTrue(item.bookable());
        assertEquals(new BigDecimal("300.00"), item.pricePerGuest(), "item carries the flat private price");
        assertTrue(item.bookingUrl().contains("private=1"), "deep link books the whole slot privately");
        assertEquals(new BigDecimal("300.00"), response.plan().estimatedBookableTotal(),
                "flat group price counts once, not x party size");
    }

    @Test
    @DisplayName("a hallucinated experienceId is dropped entirely — the planner cannot sell fiction")
    void hallucinatedExperienceIsDropped() throws Exception {
        stubWorld(modelJson("""
                {"startTimeLocal":"10:00","kind":"EXPERIENCE","title":"Invented tour",
                 "description":"Does not exist.","experienceId":"%s","slotId":"%s",
                 "placeName":null,"placeArea":null}
                """.formatted(UUID.randomUUID(), UUID.randomUUID())));

        TripPlanResponse response = tripPlanService.createPlan(request(), TRAVELER_ID);

        assertTrue(response.plan().days().get(0).items().isEmpty(), "invented experience removed");
        assertEquals(0, response.bookableItemIds().size());
    }

    @Test
    @DisplayName("an invalid slotId on a real experience is repaired to the closest free slot of that day")
    void invalidSlotIsRepaired() throws Exception {
        stubWorld(modelJson("""
                {"startTimeLocal":"15:00","kind":"EXPERIENCE","title":"Canal food walk",
                 "description":"Afternoon snacks.","experienceId":"%s","slotId":"%s",
                 "placeName":null,"placeArea":null}
                """.formatted(experience.getId(), UUID.randomUUID())));

        TripPlanResponse response = tripPlanService.createPlan(request(), TRAVELER_ID);

        TripPlanItem item = response.plan().days().get(0).items().get(0);
        assertTrue(item.bookable());
        assertEquals(afternoonSlot.getId(), item.slotId(), "repaired to the 14:00 slot (closest to 15:00)");
        assertEquals("14:00", item.startTimeLocal());
    }

    @Test
    @DisplayName("the same slot cannot be booked twice: the duplicate is repaired to another slot or demoted")
    void duplicateSlotUseIsPrevented() throws Exception {
        String item = """
                {"startTimeLocal":"10:00","kind":"EXPERIENCE","title":"Canal food walk",
                 "description":"x.","experienceId":"%s","slotId":"%s","placeName":null,"placeArea":null}
                """.formatted(experience.getId(), morningSlot.getId());
        stubWorld(modelJson(item + "," + item));

        TripPlanResponse response = tripPlanService.createPlan(request(), TRAVELER_ID);

        List<TripPlanItem> items = response.plan().days().get(0).items();
        long morningUses = items.stream().filter(i -> morningSlot.getId().equals(i.slotId())).count();
        assertEquals(1, morningUses, "the 10:00 slot is used exactly once");
        // The second item was repaired to the other slot of the day.
        assertTrue(items.stream().anyMatch(i -> afternoonSlot.getId().equals(i.slotId())));
    }

    @Test
    @DisplayName("FOOD/SIGHT items get a server-built Google Maps link; TIP items get none")
    void suggestionItemsGetMapsLinks() throws Exception {
        stubWorld(modelJson("""
                {"startTimeLocal":"09:00","kind":"FOOD","title":"Breakfast at De Bakkerswinkel",
                 "description":"Fresh scones.","experienceId":null,"slotId":null,
                 "placeName":"De Bakkerswinkel","placeArea":"Centrum"},
                {"startTimeLocal":"21:00","kind":"TIP","title":"Buy an OV-chipkaart",
                 "description":"Cheaper trams.","experienceId":null,"slotId":null,
                 "placeName":null,"placeArea":null}
                """));

        TripPlanResponse response = tripPlanService.createPlan(request(), TRAVELER_ID);

        List<TripPlanItem> items = response.plan().days().get(0).items();
        TripPlanItem food = items.get(0);
        assertEquals("FOOD", food.kind());
        assertFalse(food.bookable());
        assertNotNull(food.mapsUrl());
        assertTrue(food.mapsUrl().contains("google.com/maps"), "maps link is server-built");
        assertTrue(food.mapsUrl().contains("Amsterdam"), "maps query includes the city");

        TripPlanItem tip = items.get(1);
        assertEquals("TIP", tip.kind());
        assertNull(tip.mapsUrl());
    }
}
