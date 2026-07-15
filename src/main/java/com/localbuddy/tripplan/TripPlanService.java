package com.localbuddy.tripplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.ai.AiCallOptions;
import com.localbuddy.ai.AiMessage;
import com.localbuddy.ai.AiStructuredResult;
import com.localbuddy.ai.ClaudeClient;
import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilitySlotService;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.availability.BookingWindowPolicy;
import com.localbuddy.experience.BookingMode;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.common.exception.ServiceUnavailableException;
import com.localbuddy.deals.DealResponse;
import com.localbuddy.deals.DealService;
import com.localbuddy.experience.City;
import com.localbuddy.experience.CityRepository;
import com.localbuddy.experience.Experience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI trip planner: builds a date-aware, city-local itinerary grounded in REAL bookable
 * inventory. The model only ever sees experiences + concrete slot start times fetched from
 * the database; every experience/slot it references is validated (and repaired or demoted)
 * before the plan is persisted and served. Plans are saved with a share token so a whole
 * trip can be booked later in one bundle checkout.
 */
@Service
public class TripPlanService {

    private static final Logger log = LoggerFactory.getLogger(TripPlanService.class);

    private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Set<String> ITEM_KINDS = Set.of("EXPERIENCE", "FOOD", "SIGHT", "TIP");

    /**
     * Output contract for the model. Structured outputs guarantee the shape; the ids it
     * echoes back are still validated against the candidate inventory server-side.
     */
    private static final String OUTPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["title", "summary", "days", "tips"],
              "properties": {
                "title": {"type": "string"},
                "summary": {"type": "string"},
                "days": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["date", "theme", "items"],
                    "properties": {
                      "date": {"type": "string"},
                      "theme": {"type": "string"},
                      "items": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "additionalProperties": false,
                          "required": ["startTimeLocal", "kind", "title", "description",
                                       "experienceId", "slotId", "placeName", "placeArea"],
                          "properties": {
                            "startTimeLocal": {"type": "string", "description": "24h HH:mm local time"},
                            "kind": {"type": "string", "enum": ["EXPERIENCE", "FOOD", "SIGHT", "TIP"]},
                            "title": {"type": "string"},
                            "description": {"type": "string"},
                            "experienceId": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "slotId": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "placeName": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "placeArea": {"anyOf": [{"type": "string"}, {"type": "null"}]}
                          }
                        }
                      }
                    }
                  }
                },
                "tips": {"type": "array", "items": {"type": "string"}}
              }
            }
            """;

    private final TripPlanRepository tripPlanRepository;
    private final CityRepository cityRepository;
    private final AvailabilitySlotService availabilitySlotService;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final BookingWindowPolicy bookingWindowPolicy;
    private final DealService dealService;
    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;
    private final int maxDays;
    private final int maxExperiencesInPrompt;
    private final int maxSlotsPerExperienceDay;
    private final int maxTokens;

    public TripPlanService(
            TripPlanRepository tripPlanRepository,
            CityRepository cityRepository,
            AvailabilitySlotService availabilitySlotService,
            AvailabilitySlotRepository availabilitySlotRepository,
            BookingWindowPolicy bookingWindowPolicy,
            DealService dealService,
            ClaudeClient claudeClient,
            ObjectMapper objectMapper,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.ai.trip-planner.max-days:7}") int maxDays,
            @Value("${app.ai.trip-planner.max-experiences-in-prompt:40}") int maxExperiencesInPrompt,
            @Value("${app.ai.trip-planner.max-slots-per-experience-day:4}") int maxSlotsPerExperienceDay,
            @Value("${app.ai.trip-planner.max-tokens:12000}") int maxTokens
    ) {
        this.tripPlanRepository = tripPlanRepository;
        this.cityRepository = cityRepository;
        this.availabilitySlotService = availabilitySlotService;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.bookingWindowPolicy = bookingWindowPolicy;
        this.dealService = dealService;
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        this.maxDays = maxDays;
        this.maxExperiencesInPrompt = maxExperiencesInPrompt;
        this.maxSlotsPerExperienceDay = maxSlotsPerExperienceDay;
        this.maxTokens = maxTokens;
    }

    public boolean isConfigured() {
        return claudeClient.isConfigured();
    }

    /**
     * Generates, validates, and persists a new itinerary. Deliberately NOT transactional:
     * the model call must never run inside an open database transaction.
     */
    public TripPlanResponse createPlan(CreateTripPlanRequest request) {
        City city = cityRepository.findBySlug(request.citySlug().trim().toLowerCase())
                .filter(City::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));

        ZoneId zone = resolveZone(city);
        LocalDate today = LocalDate.now(zone);

        if (request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("End date cannot be before start date");
        }
        if (request.startDate().isBefore(today)) {
            throw new BadRequestException("Start date cannot be in the past");
        }
        long dayCount = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        if (dayCount > maxDays) {
            throw new BadRequestException("A trip plan can cover at most " + maxDays + " days");
        }

        Instant from = request.startDate().atStartOfDay(zone).toInstant();
        Instant to = request.endDate().plusDays(1).atStartOfDay(zone).toInstant();

        // Real, bookable inventory only: seat-available, cutoff-open slots for the party size.
        List<AvailabilitySlot> slots = availabilitySlotService.getBookableSlotsForCityBetween(
                city.getSlug(), from, to, request.partySize());
        Map<UUID, ExperienceInventory> inventory = groupInventory(slots, zone);

        List<DealResponse> deals = liveDealsForWindow(city, from);

        String userPrompt = buildUserPrompt(request, city, zone, dayCount, inventory, deals);
        String systemPrompt = buildSystemPrompt(dayCount);

        JsonNode schema = readSchema();
        AiStructuredResult result = claudeClient.completeStructured(
                systemPrompt,
                List.of(AiMessage.user(userPrompt)),
                schema,
                // Thinking off: the whole max_tokens budget goes to the plan JSON (adaptive
                // thinking would share it and could truncate near the cap), and generation
                // gets the extended read timeout instead of blind retries.
                AiCallOptions.longGeneration(maxTokens)
        );

        TripPlanDocument document = assembleDocument(result.json(), request, zone, inventory, city);

        TripPlan tripPlan = new TripPlan();
        tripPlan.setToken(generateToken());
        tripPlan.setCity(city);
        tripPlan.setStartDate(request.startDate());
        tripPlan.setEndDate(request.endDate());
        tripPlan.setPartySize(request.partySize());
        tripPlan.setInterests(trimToNull(request.interests()));
        tripPlan.setNotes(trimToNull(request.notes()));
        tripPlan.setStatus(TripPlanStatus.ACTIVE);
        tripPlan.setPlan(writeDocument(document));
        tripPlan.setModel(claudeClient.getModel());
        tripPlan.setInputTokens(result.inputTokens());
        tripPlan.setOutputTokens(result.outputTokens());
        tripPlan = tripPlanRepository.save(tripPlan);

        return toResponse(tripPlan, city, document);
    }

    /** A saved plan with each bookable item's availability re-checked against live inventory. */
    @Transactional(readOnly = true)
    public TripPlanResponse getPlanByToken(String token) {
        TripPlan tripPlan = tripPlanRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Trip plan not found"));

        TripPlanDocument document = readDocument(tripPlan.getPlan());
        TripPlanDocument refreshed = refreshAvailability(document, tripPlan.getPartySize());

        return toResponse(tripPlan, tripPlan.getCity(), refreshed);
    }

    /** Entity + parsed document for the bundle-checkout flow. */
    @Transactional(readOnly = true)
    public TripPlanWithDocument getPlanEntityByToken(String token) {
        TripPlan tripPlan = tripPlanRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Trip plan not found"));
        return new TripPlanWithDocument(tripPlan.getId(), tripPlan.getPartySize(),
                readDocument(tripPlan.getPlan()));
    }

    public record TripPlanWithDocument(UUID tripPlanId, Integer partySize, TripPlanDocument document) {
    }

    // ------------------------------------------------------------------
    // Inventory + prompt building
    // ------------------------------------------------------------------

    private record ExperienceInventory(
            Experience experience,
            Map<LocalDate, List<AvailabilitySlot>> slotsByDate,
            Map<UUID, AvailabilitySlot> slotsById
    ) {
    }

    private Map<UUID, ExperienceInventory> groupInventory(List<AvailabilitySlot> slots, ZoneId zone) {
        Map<UUID, List<AvailabilitySlot>> byExperience = slots.stream()
                .collect(Collectors.groupingBy(slot -> slot.getExperience().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        // Rank experiences (best-rated hosts first, then soonest availability) and cap the
        // prompt size; cap slots listed per experience per day as well.
        List<Map.Entry<UUID, List<AvailabilitySlot>>> ranked = byExperience.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<UUID, List<AvailabilitySlot>> entry) ->
                                        hostRating(entry.getValue().get(0).getExperience()),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getValue().get(0).getStartTime()))
                .limit(maxExperiencesInPrompt)
                .toList();

        Map<UUID, ExperienceInventory> inventory = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<AvailabilitySlot>> entry : ranked) {
            Experience experience = entry.getValue().get(0).getExperience();

            Map<LocalDate, List<AvailabilitySlot>> byDate = new LinkedHashMap<>();
            for (AvailabilitySlot slot : entry.getValue()) {
                LocalDate date = slot.getStartTime().atZone(zone).toLocalDate();
                List<AvailabilitySlot> daySlots = byDate.computeIfAbsent(date, d -> new ArrayList<>());
                if (daySlots.size() < maxSlotsPerExperienceDay) {
                    daySlots.add(slot);
                }
            }

            Map<UUID, AvailabilitySlot> byId = byDate.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toMap(AvailabilitySlot::getId, Function.identity()));

            inventory.put(experience.getId(), new ExperienceInventory(experience, byDate, byId));
        }
        return inventory;
    }

    private BigDecimal hostRating(Experience experience) {
        return experience.getLocalProfile() != null ? experience.getLocalProfile().getRatingAvg() : null;
    }

    private List<DealResponse> liveDealsForWindow(City city, Instant tripStart) {
        try {
            return dealService.listLiveDeals(null, city.getId(), null, null).stream()
                    .filter(deal -> deal.endsAt() == null || !deal.endsAt().isBefore(tripStart))
                    .limit(5)
                    .toList();
        } catch (Exception ex) {
            // Deals are decorative for the planner; never let them fail plan generation.
            log.warn("Skipping deals in trip-plan prompt: {}", ex.getMessage());
            return List.of();
        }
    }

    private String buildSystemPrompt(long dayCount) {
        return """
                You are the AI trip planner for LocalBuddy, a marketplace where travelers book small-group \
                experiences hosted by locals. You design warm, realistic, city-local itineraries.

                HARD RULES:
                - Return exactly %d day(s), using the exact dates provided, in order.
                - LocalBuddy experiences may ONLY come from the provided list, and ONLY at one of the listed \
                slot start times for that same date: set kind="EXPERIENCE" and copy the experienceId and the \
                chosen slotId EXACTLY as given. Never invent experiences, slots, or times; never reuse a slotId twice.
                - Weave 1-3 LocalBuddy experiences into each day when available, at their real slot times. \
                They are the highlights of the trip — schedule the rest of the day around them.
                - Non-bookable items are suggestions: kind="FOOD" for cafes/restaurants/markets, kind="SIGHT" for \
                viewpoints/parks/landmarks/neighborhoods (including a sunset spot), kind="TIP" for practical advice. \
                For FOOD and SIGHT give a real, well-known placeName plus its neighborhood in placeArea; prefer \
                beloved local spots over tourist traps. Do not invent opening hours or prices. \
                For these items experienceId and slotId must be null.
                - Structure each day roughly: breakfast (FOOD), a morning activity, lunch (FOOD), an afternoon \
                activity, a late-afternoon/sunset moment (SIGHT), and an evening plan (FOOD or an evening EXPERIENCE).
                - startTimeLocal is 24h "HH:mm" local time. Order items chronologically. No overlaps: respect each \
                experience's durationMinutes and leave at least 30 minutes of travel time between consecutive items.
                - Descriptions: 1-2 engaging sentences each. Mention a deal briefly in the description only if that \
                exact experience has one listed.
                - Honor the traveler's interests, notes, dietary hints, and party size.
                - If the provided experience list is empty or sparse, still produce an excellent local itinerary \
                from FOOD/SIGHT/TIP items and say so in the summary.
                - tips: 2-5 short practical tips for this city and season.
                """.formatted(dayCount);
    }

    private String buildUserPrompt(
            CreateTripPlanRequest request,
            City city,
            ZoneId zone,
            long dayCount,
            Map<UUID, ExperienceInventory> inventory,
            List<DealResponse> deals
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CITY: ").append(city.getName()).append(", ").append(city.getCountry())
                .append(" (timezone ").append(zone).append(")\n");
        sb.append("DATES: ").append(request.startDate()).append(" to ").append(request.endDate())
                .append(" (").append(dayCount).append(" day(s))\n");
        sb.append("PARTY SIZE: ").append(request.partySize()).append("\n");
        if (notBlank(request.interests())) {
            sb.append("INTERESTS: ").append(request.interests().trim()).append("\n");
        }
        if (notBlank(request.notes())) {
            sb.append("NOTES: ").append(request.notes().trim()).append("\n");
        }

        if (!deals.isEmpty()) {
            sb.append("\nACTIVE DEALS (mention only when the deal's experience is used):\n");
            for (DealResponse deal : deals) {
                sb.append("- ").append(deal.name());
                if (notBlank(deal.badgeText())) {
                    sb.append(" [").append(deal.badgeText()).append("]");
                }
                if (deal.targetExperienceId() != null) {
                    sb.append(" (experienceId ").append(deal.targetExperienceId()).append(")");
                }
                sb.append("\n");
            }
        }

        sb.append("\nBOOKABLE LOCALBUDDY EXPERIENCES (the ONLY allowed EXPERIENCE items):\n");
        if (inventory.isEmpty()) {
            sb.append("(none available for these dates)\n");
        }
        for (ExperienceInventory entry : inventory.values()) {
            Experience experience = entry.experience();
            sb.append("\n* experienceId: ").append(experience.getId()).append("\n");
            sb.append("  title: ").append(experience.getTitle()).append("\n");
            if (experience.getCategory() != null) {
                sb.append("  category: ").append(experience.getCategory().getName()).append("\n");
            }
            sb.append("  durationMinutes: ").append(experience.getDurationMinutes()).append("\n");
            if (experience.getPriceAmount() != null) {
                sb.append("  pricePerGuest: ").append(experience.getPriceAmount())
                        .append(" ").append(experience.getCurrency()).append("\n");
            }
            if (experience.getLocalProfile() != null && experience.getLocalProfile().getRatingAvg() != null) {
                sb.append("  hostRating: ").append(experience.getLocalProfile().getRatingAvg())
                        .append(" (").append(experience.getLocalProfile().getTotalReviews()).append(" reviews)\n");
            }
            if (notBlank(experience.getMeetingArea())) {
                sb.append("  meetingArea: ").append(experience.getMeetingArea()).append("\n");
            }
            String about = notBlank(experience.getShortDescription())
                    ? experience.getShortDescription()
                    : experience.getDescription();
            if (notBlank(about)) {
                sb.append("  about: ").append(truncate(about.replaceAll("\\s+", " ").trim(), 240)).append("\n");
            }
            sb.append("  slots:\n");
            for (Map.Entry<LocalDate, List<AvailabilitySlot>> day : entry.slotsByDate().entrySet()) {
                sb.append("    ").append(day.getKey()).append(": ");
                sb.append(day.getValue().stream()
                        .map(slot -> TIME_FORMAT.format(slot.getStartTime().atZone(zone))
                                + " (slotId " + slot.getId() + ")")
                        .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Assembly: validate, repair, enrich
    // ------------------------------------------------------------------

    private TripPlanDocument assembleDocument(
            JsonNode modelJson,
            CreateTripPlanRequest request,
            ZoneId zone,
            Map<UUID, ExperienceInventory> inventory,
            City city
    ) {
        List<LocalDate> expectedDates = new ArrayList<>();
        for (LocalDate date = request.startDate(); !date.isAfter(request.endDate()); date = date.plusDays(1)) {
            expectedDates.add(date);
        }

        String currency = inventory.values().stream()
                .map(entry -> entry.experience().getCurrency())
                .filter(this::notBlank)
                .findFirst()
                .orElse("EUR")
                .toUpperCase();

        JsonNode modelDays = modelJson.path("days");
        Set<UUID> usedSlotIds = new HashSet<>();
        List<TripPlanDay> days = new ArrayList<>();
        BigDecimal estimatedTotal = BigDecimal.ZERO;

        for (int dayIndex = 0; dayIndex < expectedDates.size(); dayIndex++) {
            LocalDate date = expectedDates.get(dayIndex);
            JsonNode modelDay = dayIndex < modelDays.size() ? modelDays.get(dayIndex) : null;

            List<TripPlanItem> items = new ArrayList<>();
            if (modelDay != null) {
                JsonNode modelItems = modelDay.path("items");
                for (int itemIndex = 0; itemIndex < modelItems.size(); itemIndex++) {
                    TripPlanItem item = assembleItem(
                            modelItems.get(itemIndex),
                            "d" + (dayIndex + 1) + "-i" + (itemIndex + 1),
                            date, zone, inventory, usedSlotIds, request.partySize(), city);
                    if (item != null) {
                        items.add(item);
                        if (item.bookable() && item.pricePerGuest() != null) {
                            estimatedTotal = estimatedTotal.add(
                                    item.pricePerGuest().multiply(BigDecimal.valueOf(request.partySize())));
                        }
                    }
                }
            }

            items.sort(Comparator.comparingInt(item -> parseMinutesOfDay(item.startTimeLocal())));

            String theme = modelDay != null ? truncate(modelDay.path("theme").asText(""), 160) : "";
            days.add(new TripPlanDay(date, theme, items));
        }

        List<String> tips = new ArrayList<>();
        for (JsonNode tip : modelJson.path("tips")) {
            if (tips.size() >= 6) {
                break;
            }
            String text = tip.asText("").trim();
            if (!text.isEmpty()) {
                tips.add(truncate(text, 300));
            }
        }

        return new TripPlanDocument(
                truncate(modelJson.path("title").asText("Your trip plan"), 160),
                truncate(modelJson.path("summary").asText(""), 1000),
                currency,
                estimatedTotal.setScale(2, java.math.RoundingMode.HALF_UP),
                days,
                tips
        );
    }

    private TripPlanItem assembleItem(
            JsonNode modelItem,
            String itemId,
            LocalDate date,
            ZoneId zone,
            Map<UUID, ExperienceInventory> inventory,
            Set<UUID> usedSlotIds,
            int partySize,
            City city
    ) {
        String kind = modelItem.path("kind").asText("");
        if (!ITEM_KINDS.contains(kind)) {
            return null;
        }

        String title = truncate(modelItem.path("title").asText("").trim(), 160);
        String description = truncate(modelItem.path("description").asText("").trim(), 500);
        String startTimeLocal = normalizeTime(modelItem.path("startTimeLocal").asText(""));
        if (title.isEmpty()) {
            return null;
        }

        if (!"EXPERIENCE".equals(kind)) {
            String placeName = truncate(textOrNull(modelItem, "placeName"), 160);
            String placeArea = truncate(textOrNull(modelItem, "placeArea"), 120);
            String mapsUrl = null;
            if ("TIP".equals(kind)) {
                placeName = null;
            } else if (placeName != null) {
                mapsUrl = mapsSearchUrl(placeName, placeArea, city);
            }
            return new TripPlanItem(itemId, startTimeLocal, kind, title, description,
                    null, null, null, null, null, null, null, null, null,
                    placeName, mapsUrl, false, null);
        }

        // EXPERIENCE: everything the model claims is validated against real inventory.
        UUID experienceId = parseUuid(textOrNull(modelItem, "experienceId"));
        ExperienceInventory entry = experienceId != null ? inventory.get(experienceId) : null;
        if (entry == null) {
            // Hallucinated experience: drop the item entirely rather than sell fiction.
            return null;
        }

        Experience experience = entry.experience();
        UUID slotId = parseUuid(textOrNull(modelItem, "slotId"));
        AvailabilitySlot slot = slotId != null ? entry.slotsById().get(slotId) : null;

        // The chosen slot must exist, be for THIS day, and not already be used by another item.
        if (slot != null) {
            LocalDate slotDate = slot.getStartTime().atZone(zone).toLocalDate();
            if (!slotDate.equals(date) || usedSlotIds.contains(slot.getId())) {
                slot = null;
            }
        }
        // Repair: pick the day's slot closest to the model's intended time.
        if (slot == null) {
            slot = pickClosestFreeSlot(entry.slotsByDate().get(date), usedSlotIds, startTimeLocal, zone);
        }

        String experienceUrl = frontendBaseUrl + "/experience/" + experience.getSlug();

        if (slot == null) {
            // No bookable slot this day: keep it as a non-bookable recommendation with a link
            // to the experience page (it may be bookable on other dates).
            return new TripPlanItem(itemId, startTimeLocal, kind, title, description,
                    experience.getId(), experience.getSlug(), experience.getTitle(), experienceUrl,
                    null, null, null, null, experience.getPriceAmount(),
                    null, experienceMapsUrl(experience), false, null);
        }

        usedSlotIds.add(slot.getId());
        String slotTimeLocal = TIME_FORMAT.format(slot.getStartTime().atZone(zone));
        String bookingUrl = frontendBaseUrl + "/experience/" + experience.getId() + "/book"
                + "?date=" + date
                + "&slot=" + urlEncode(slotTimeLocal)
                + "&guests=" + partySize
                + "&adults=" + partySize;

        return new TripPlanItem(itemId, slotTimeLocal, kind, title, description,
                experience.getId(), experience.getSlug(), experience.getTitle(), experienceUrl,
                bookingUrl, slot.getId(), slot.getStartTime(), slot.getEndTime(),
                experience.getPriceAmount(), null, experienceMapsUrl(experience), true, null);
    }

    private AvailabilitySlot pickClosestFreeSlot(
            List<AvailabilitySlot> daySlots,
            Set<UUID> usedSlotIds,
            String intendedTime,
            ZoneId zone
    ) {
        if (daySlots == null || daySlots.isEmpty()) {
            return null;
        }
        int intendedMinutes = parseMinutesOfDay(intendedTime);
        return daySlots.stream()
                .filter(slot -> !usedSlotIds.contains(slot.getId()))
                .min(Comparator.comparingInt(slot -> {
                    int slotMinutes = slot.getStartTime().atZone(zone).toLocalTime().toSecondOfDay() / 60;
                    return Math.abs(slotMinutes - (intendedMinutes == Integer.MAX_VALUE ? 12 * 60 : intendedMinutes));
                }))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Reads: availability refresh
    // ------------------------------------------------------------------

    private TripPlanDocument refreshAvailability(TripPlanDocument document, Integer partySize) {
        List<UUID> slotIds = document.days().stream()
                .flatMap(day -> day.items().stream())
                .filter(TripPlanItem::bookable)
                .map(TripPlanItem::slotId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<UUID, AvailabilitySlot> slots = slotIds.isEmpty()
                ? Map.of()
                : availabilitySlotRepository.findAllById(slotIds).stream()
                        .collect(Collectors.toMap(AvailabilitySlot::getId, Function.identity()));

        Instant now = Instant.now();
        int neededSeats = partySize != null ? partySize : 1;

        List<TripPlanDay> refreshedDays = document.days().stream()
                .map(day -> day.withItems(day.items().stream()
                        .map(item -> {
                            if (!item.bookable()) {
                                return item.withAvailable(null);
                            }
                            AvailabilitySlot slot = slots.get(item.slotId());
                            boolean available = slot != null
                                    && slot.getStatus() == AvailabilityStatus.AVAILABLE
                                    && slot.getCapacity() - slot.getBookedCount() >= neededSeats
                                    && bookingWindowPolicy.isBookableAt(slot, now)
                                    // The experience itself must still be sellable per guest
                                    // (it may have been paused/blocked or switched to
                                    // buyout-only since the plan was generated).
                                    && slot.getExperience().getStatus() == ExperienceStatus.APPROVED
                                    && slot.getExperience().getBookingMode() != BookingMode.PRIVATE_ONLY;
                            return item.withAvailable(available);
                        })
                        .toList()))
                .toList();

        return document.withDays(refreshedDays);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TripPlanResponse toResponse(TripPlan tripPlan, City city, TripPlanDocument document) {
        String shareUrl = frontendBaseUrl + "/trip-planner/plan/" + tripPlan.getToken();
        List<String> bookableItemIds = document.days().stream()
                .flatMap(day -> day.items().stream())
                .filter(TripPlanItem::bookable)
                .map(TripPlanItem::id)
                .toList();
        return new TripPlanResponse(
                tripPlan.getToken(),
                shareUrl,
                shareUrl + "?select=all",
                city.getSlug(),
                city.getName(),
                city.getCountry(),
                tripPlan.getStartDate(),
                tripPlan.getEndDate(),
                tripPlan.getPartySize(),
                tripPlan.getInterests(),
                document,
                bookableItemIds,
                tripPlan.getCreatedAt()
        );
    }

    private ZoneId resolveZone(City city) {
        try {
            return ZoneId.of(city.getTimezone());
        } catch (Exception ex) {
            return ZoneId.of("Europe/Amsterdam");
        }
    }

    private JsonNode readSchema() {
        try {
            return objectMapper.readTree(OUTPUT_SCHEMA_JSON);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid trip-plan output schema", ex);
        }
    }

    private String writeDocument(TripPlanDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (Exception ex) {
            throw new ServiceUnavailableException("Could not store the generated trip plan");
        }
    }

    private TripPlanDocument readDocument(String json) {
        try {
            return objectMapper.readValue(json, TripPlanDocument.class);
        } catch (Exception ex) {
            log.error("Stored trip plan document failed to parse", ex);
            throw new ServiceUnavailableException("This trip plan could not be loaded");
        }
    }

    private String mapsSearchUrl(String placeName, String placeArea, City city) {
        String query = placeName
                + (notBlank(placeArea) ? ", " + placeArea : "")
                + ", " + city.getName();
        return "https://www.google.com/maps/search/?api=1&query=" + urlEncode(query);
    }

    private String experienceMapsUrl(Experience experience) {
        if (experience.getLatitude() != null && experience.getLongitude() != null) {
            return "https://www.google.com/maps/search/?api=1&query="
                    + experience.getLatitude().toPlainString() + "%2C" + experience.getLongitude().toPlainString();
        }
        if (notBlank(experience.getMeetingArea())) {
            return "https://www.google.com/maps/search/?api=1&query="
                    + urlEncode(experience.getMeetingArea() + ", " + experience.getCity().getName());
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int parseMinutesOfDay(String time) {
        if (time == null || time.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            LocalTime parsed = LocalTime.parse(time, TIME_FORMAT);
            return parsed.toSecondOfDay() / 60;
        } catch (Exception ex) {
            return Integer.MAX_VALUE;
        }
    }

    private String normalizeTime(String time) {
        if (time == null) {
            return "";
        }
        String trimmed = time.trim();
        try {
            return TIME_FORMAT.format(LocalTime.parse(trimmed, TIME_FORMAT));
        } catch (Exception ex) {
            return "";
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength - 1) + "…" : value;
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(TOKEN_RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }
}
