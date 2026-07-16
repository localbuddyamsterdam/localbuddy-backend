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
import com.localbuddy.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
                "title": {"type": "string", "description": "Max 6 words"},
                "summary": {"type": "string", "description": "Exactly 2 sentences"},
                "days": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["date", "theme", "items"],
                    "properties": {
                      "date": {"type": "string"},
                      "theme": {"type": "string", "description": "3-5 words"},
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
                            "description": {"type": "string", "description": "One vivid sentence, max 18 words"},
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
                "tips": {"type": "array", "items": {"type": "string", "description": "One line, max 12 words"}}
              }
            }
            """;

    private final TripPlanRepository tripPlanRepository;
    private final UserRepository userRepository;
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
            UserRepository userRepository,
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
        this.userRepository = userRepository;
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
     * Generates, validates, and persists a new itinerary owned by {@code userId}. Deliberately
     * NOT transactional: the model call must never run inside an open database transaction.
     */
    public TripPlanResponse createPlan(CreateTripPlanRequest request, UUID userId) {
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

        boolean privateTour = request.isPrivateTour();

        // Real, bookable inventory only. Shared mode: seat-available, cutoff-open slots for the
        // party size. Private mode: empty slots of buyout-capable experiences (flat private price).
        List<AvailabilitySlot> slots = privateTour
                ? availabilitySlotService.getPrivateBuyoutSlotsForCityBetween(
                        city.getSlug(), from, to, request.partySize())
                : availabilitySlotService.getBookableSlotsForCityBetween(
                        city.getSlug(), from, to, request.partySize());
        Map<UUID, ExperienceInventory> inventory = groupInventory(slots, zone);

        List<DealResponse> deals = liveDealsForWindow(city, from);

        String language = request.languageOrDefault();
        String userPrompt = buildUserPrompt(request, city, zone, dayCount, inventory, deals);
        String systemPrompt = buildSystemPrompt(dayCount, privateTour, language);

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
        tripPlan.setUser(userRepository.getReferenceById(userId));
        tripPlan.setStartDate(request.startDate());
        tripPlan.setEndDate(request.endDate());
        tripPlan.setPartySize(request.partySize());
        tripPlan.setPrivateTour(privateTour);
        tripPlan.setInterests(trimToNull(request.interests()));
        tripPlan.setNotes(trimToNull(request.notes()));
        tripPlan.setStatus(TripPlanStatus.ACTIVE);
        tripPlan.setLanguage(language);
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
        TripPlan tripPlan = findActivePlanByToken(token);

        TripPlanDocument document = readDocument(tripPlan.getPlan());
        TripPlanDocument refreshed = refreshAvailability(document, tripPlan.getPartySize(), tripPlan.isPrivateTour());

        return toResponse(tripPlan, tripPlan.getCity(), refreshed);
    }

    /** Entity + parsed document for the bundle-checkout flow. */
    @Transactional(readOnly = true)
    public TripPlanWithDocument getPlanEntityByToken(String token) {
        TripPlan tripPlan = findActivePlanByToken(token);
        return new TripPlanWithDocument(tripPlan.getId(), tripPlan.getPartySize(),
                tripPlan.isPrivateTour(), readDocument(tripPlan.getPlan()));
    }

    /** An archived plan is gone as far as the share link is concerned — viewing and checkout both 404. */
    private TripPlan findActivePlanByToken(String token) {
        return tripPlanRepository.findByToken(token)
                .filter(plan -> plan.getStatus() == TripPlanStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Trip plan not found"));
    }

    public record TripPlanWithDocument(UUID tripPlanId, Integer partySize, boolean privateTour,
                                       TripPlanDocument document) {
    }

    /** "My itineraries" (account tab): lightweight rows, newest first — no availability refresh. */
    @Transactional(readOnly = true)
    public Page<TripPlanSummaryResponse> listMine(UUID userId, Pageable pageable) {
        return tripPlanRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(plan -> new TripPlanSummaryResponse(
                        plan.getToken(),
                        extractTitle(plan.getPlan()),
                        plan.getCity().getSlug(),
                        plan.getCity().getName(),
                        plan.getStartDate(),
                        plan.getEndDate(),
                        plan.getPartySize(),
                        plan.getCreatedAt()
                ));
    }

    /** Reads just the "title" field from the stored plan JSON — no full document deserialization. */
    private String extractTitle(String planJson) {
        try {
            return objectMapper.readTree(planJson).path("title").asText("Your trip plan");
        } catch (Exception ex) {
            return "Your trip plan";
        }
    }

    /**
     * One-tap "was this itinerary helpful?" vote. Anyone with the share link may vote (the token
     * is unguessable); a repeat vote simply overwrites — the point is a per-model quality signal,
     * not a poll.
     */
    @Transactional
    public void recordFeedback(String token, boolean helpful) {
        TripPlan tripPlan = findActivePlanByToken(token);
        tripPlan.setFeedbackHelpful(helpful);
        tripPlan.setFeedbackAt(Instant.now());
        tripPlanRepository.save(tripPlan);
        log.info("Trip plan feedback: token={} helpful={} model={}", token, helpful, tripPlan.getModel());
    }

    /**
     * Self-healing for a sold-out itinerary item (owner only): verifies the item's slot really is
     * gone, then swaps in the closest live alternative for the same day — preferring another slot
     * of the same experience, otherwise an experience not already in the plan — and re-saves the
     * plan with a recomputed bookable total.
     */
    @Transactional
    public TripPlanResponse replaceSoldOutItem(UUID userId, String token, String itemId) {
        TripPlan tripPlan = findActivePlanByToken(token);
        // 404 (not 403) for non-owners so the endpoint doesn't confirm a token exists.
        if (tripPlan.getUser() == null || !tripPlan.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Trip plan not found");
        }

        TripPlanDocument document = readDocument(tripPlan.getPlan());
        City city = tripPlan.getCity();
        ZoneId zone = resolveZone(city);
        int partySize = tripPlan.getPartySize() != null ? tripPlan.getPartySize() : 1;
        boolean privateTour = tripPlan.isPrivateTour();
        Instant now = Instant.now();

        TripPlanDay targetDay = null;
        TripPlanItem target = null;
        for (TripPlanDay day : document.days()) {
            for (TripPlanItem item : day.items()) {
                if (item.id().equals(itemId)) {
                    targetDay = day;
                    target = item;
                }
            }
        }
        if (target == null || !target.bookable() || target.slotId() == null) {
            throw new BadRequestException("This item cannot be replaced");
        }

        // Live re-check — the client saying "sold out" isn't trusted.
        AvailabilitySlot currentSlot = availabilitySlotRepository
                .findAllWithExperienceByIdIn(List.of(target.slotId()))
                .stream().findFirst().orElse(null);
        if (isSlotStillBookable(currentSlot, partySize, privateTour, now)) {
            throw new BadRequestException("This item is still bookable — no replacement needed");
        }

        LocalDate date = targetDay.date();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<AvailabilitySlot> daySlots = privateTour
                ? availabilitySlotService.getPrivateBuyoutSlotsForCityBetween(city.getSlug(), from, to, partySize)
                : availabilitySlotService.getBookableSlotsForCityBetween(city.getSlug(), from, to, partySize);

        Set<UUID> usedSlotIds = document.days().stream()
                .flatMap(day -> day.items().stream())
                .map(TripPlanItem::slotId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> plannedExperienceIds = document.days().stream()
                .flatMap(day -> day.items().stream())
                .map(TripPlanItem::experienceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        UUID soldOutExperienceId = target.experienceId();

        List<AvailabilitySlot> candidates = daySlots.stream()
                .filter(slot -> !usedSlotIds.contains(slot.getId()))
                .filter(slot -> slot.getExperience().getId().equals(soldOutExperienceId)
                        || !plannedExperienceIds.contains(slot.getExperience().getId()))
                .toList();

        String intendedTime = target.startTimeLocal();
        AvailabilitySlot replacementSlot = pickClosestFreeSlot(
                candidates.stream()
                        .filter(slot -> slot.getExperience().getId().equals(soldOutExperienceId))
                        .toList(),
                Set.of(), intendedTime, zone);
        boolean sameExperience = replacementSlot != null;
        if (replacementSlot == null) {
            replacementSlot = pickClosestFreeSlot(candidates, Set.of(), intendedTime, zone);
        }
        if (replacementSlot == null) {
            throw new ResourceNotFoundException("No bookable alternative was found for that day");
        }

        Experience experience = replacementSlot.getExperience();
        String slotTimeLocal = TIME_FORMAT.format(replacementSlot.getStartTime().atZone(zone));
        BigDecimal itemPrice = privateTour ? experience.getPrivatePrice() : experience.getPriceAmount();
        String description = sameExperience
                ? target.description()
                : replacementNote(tripPlan.getLanguage()).formatted(target.title());

        TripPlanItem replacement = new TripPlanItem(
                target.id(), slotTimeLocal, "EXPERIENCE",
                sameExperience ? target.title() : experience.getTitle(),
                description,
                experience.getId(), experience.getSlug(), experience.getTitle(),
                frontendBaseUrl + "/experience/" + experience.getSlug(),
                buildBookingUrl(experience, date, slotTimeLocal, partySize, privateTour),
                replacementSlot.getId(), replacementSlot.getStartTime(), replacementSlot.getEndTime(),
                itemPrice, null, experienceMapsUrl(experience), true, null);

        List<TripPlanDay> newDays = document.days().stream()
                .map(day -> day.withItems(day.items().stream()
                        .map(item -> item.id().equals(itemId) ? replacement : item)
                        .toList()))
                .toList();
        TripPlanDocument updated = recomputeEstimatedTotal(document.withDays(newDays), partySize, privateTour);

        tripPlan.setPlan(writeDocument(updated));
        tripPlan = tripPlanRepository.save(tripPlan);

        return toResponse(tripPlan, city, refreshAvailability(updated, partySize, privateTour));
    }

    /** Note shown on a swapped-in item, in the plan's own language. */
    private String replacementNote(String language) {
        return switch (language == null ? "en" : language) {
            case "nl" -> "Vervanging voor \"%s\", dat niet meer beschikbaar is.";
            case "fr" -> "Remplacement de « %s », qui n'est plus disponible.";
            default -> "Replacement for \"%s\", which is no longer available.";
        };
    }

    /** Same math as initial assembly: private = flat buyout price, shared = per-guest × party. */
    private TripPlanDocument recomputeEstimatedTotal(TripPlanDocument document, int partySize, boolean privateTour) {
        BigDecimal total = document.days().stream()
                .flatMap(day -> day.items().stream())
                .filter(TripPlanItem::bookable)
                .map(TripPlanItem::pricePerGuest)
                .filter(java.util.Objects::nonNull)
                .map(price -> privateTour ? price : price.multiply(BigDecimal.valueOf(partySize)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TripPlanDocument(document.title(), document.summary(), document.currency(),
                total, document.days(), document.tips());
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

    private String buildSystemPrompt(long dayCount, boolean privateTour, String language) {
        String privateRule = privateTour
                ? """
                - PRIVATE TOURS: the traveler booked this trip as private tours — every listed \
                experience is reserved exclusively for their group (no other guests join), and the \
                listed privateTotalPrice is the flat price for the whole group, not per person. \
                Reflect this exclusivity naturally in your descriptions.
                """
                : "";
        String languageName = switch (language) {
            case "nl" -> "Dutch";
            case "fr" -> "French";
            default -> "English";
        };
        String languageRule = "en".equals(language) ? "" :
                "- LANGUAGE: write every human-readable text (title, summary, day themes, descriptions, "
                        + "FOOD/SIGHT/TIP titles, tips) in " + languageName + ". Keep proper nouns and place "
                        + "names in their local form, and copy LocalBuddy experience titles EXACTLY as given — "
                        + "never translate them.\n";
        return ("""
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
                - BREVITY (the plan is skimmed on a phone — short, punchy, concrete): title max 6 words; \
                summary exactly 2 sentences; each day's theme 3-5 words; each item description exactly ONE \
                vivid sentence of at most 18 words. No filler phrases like "immerse yourself" or "hidden gem" — \
                name the concrete thing that makes the place worth it. Mention a deal in a description only \
                if that exact experience has one listed.
                - Honor the traveler's interests, notes, dietary hints, and party size.
                - If the provided experience list is empty or sparse, still produce an excellent local itinerary \
                from FOOD/SIGHT/TIP items and say so in the summary.
                - tips: exactly 3 practical one-line tips (max 12 words each) for this city and season.
                """ + privateRule + languageRule).formatted(dayCount);
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
        if (request.isPrivateTour()) {
            sb.append("TOUR TYPE: PRIVATE — each experience is a whole-group private buyout\n");
        }
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
            if (request.isPrivateTour()) {
                if (experience.getPrivatePrice() != null) {
                    sb.append("  privateTotalPrice: ").append(experience.getPrivatePrice())
                            .append(" ").append(experience.getCurrency())
                            .append(" (flat, whole group)\n");
                }
            } else if (experience.getPriceAmount() != null) {
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
                            date, zone, inventory, usedSlotIds, request.partySize(),
                            request.isPrivateTour(), city);
                    if (item != null) {
                        items.add(item);
                        if (item.bookable() && item.pricePerGuest() != null) {
                            // Private tours carry the flat whole-group price on the item, so it
                            // counts once; shared items are per guest.
                            estimatedTotal = estimatedTotal.add(request.isPrivateTour()
                                    ? item.pricePerGuest()
                                    : item.pricePerGuest().multiply(BigDecimal.valueOf(request.partySize())));
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
            boolean privateTour,
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
        // Private plans carry the flat whole-group buyout price; shared plans the per-guest price.
        BigDecimal itemPrice = privateTour ? experience.getPrivatePrice() : experience.getPriceAmount();

        if (slot == null) {
            // No bookable slot this day: keep it as a non-bookable recommendation with a link
            // to the experience page (it may be bookable on other dates).
            return new TripPlanItem(itemId, startTimeLocal, kind, title, description,
                    experience.getId(), experience.getSlug(), experience.getTitle(), experienceUrl,
                    null, null, null, null, itemPrice,
                    null, experienceMapsUrl(experience), false, null);
        }

        usedSlotIds.add(slot.getId());
        String slotTimeLocal = TIME_FORMAT.format(slot.getStartTime().atZone(zone));
        String bookingUrl = buildBookingUrl(experience, date, slotTimeLocal, partySize, privateTour);

        return new TripPlanItem(itemId, slotTimeLocal, kind, title, description,
                experience.getId(), experience.getSlug(), experience.getTitle(), experienceUrl,
                bookingUrl, slot.getId(), slot.getStartTime(), slot.getEndTime(),
                itemPrice, null, experienceMapsUrl(experience), true, null);
    }

    /** Deep link that starts checkout pre-filled; ?private=1 books the whole slot at the flat private price. */
    private String buildBookingUrl(Experience experience, LocalDate date, String slotTimeLocal,
                                   int partySize, boolean privateTour) {
        return frontendBaseUrl + "/experience/" + experience.getId() + "/book"
                + "?date=" + date
                + "&slot=" + urlEncode(slotTimeLocal)
                + "&guests=" + partySize
                + "&adults=" + partySize
                + (privateTour ? "&private=1" : "");
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

    private TripPlanDocument refreshAvailability(TripPlanDocument document, Integer partySize, boolean privateTour) {
        List<UUID> slotIds = document.days().stream()
                .flatMap(day -> day.items().stream())
                .filter(TripPlanItem::bookable)
                .map(TripPlanItem::slotId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<UUID, AvailabilitySlot> slots = slotIds.isEmpty()
                ? Map.of()
                : availabilitySlotRepository.findAllWithExperienceByIdIn(slotIds).stream()
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
                            return item.withAvailable(isSlotStillBookable(slot, neededSeats, privateTour, now));
                        })
                        .toList()))
                .toList();

        return document.withDays(refreshedDays);
    }

    /**
     * Whether one specific slot can still be booked for this plan's party. Private buyout: the
     * slot must be untouched (a single shared guest removes the private option) and the experience
     * must still offer a private price. Shared: enough seats left and the experience still
     * sellable per guest (it may have switched to buyout-only since the plan was generated).
     */
    private boolean isSlotStillBookable(AvailabilitySlot slot, int neededSeats, boolean privateTour, Instant now) {
        return slot != null
                && slot.getStatus() == AvailabilityStatus.AVAILABLE
                && bookingWindowPolicy.isBookableAt(slot, now)
                && slot.getExperience().getStatus() == ExperienceStatus.APPROVED
                && (privateTour
                        ? slot.getBookedCount() == 0
                                && slot.getCapacity() >= neededSeats
                                && slot.getExperience().getBookingMode() != BookingMode.SHARED
                                && slot.getExperience().getPrivatePrice() != null
                        : slot.getCapacity() - slot.getBookedCount() >= neededSeats
                                && slot.getExperience().getBookingMode() != BookingMode.PRIVATE_ONLY);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TripPlanResponse toResponse(TripPlan tripPlan, City city, TripPlanDocument document) {
        // Must match the Angular route exactly: /trip-planner/:token (no extra path segment).
        String shareUrl = frontendBaseUrl + "/trip-planner/" + tripPlan.getToken();
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
                tripPlan.isPrivateTour(),
                tripPlan.getInterests(),
                tripPlan.getLanguage(),
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
