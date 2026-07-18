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
import java.time.ZonedDateTime;
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

    /** Product rule: a day never carries more than two experiences (prompted AND enforced). */
    private static final int MAX_EXPERIENCES_PER_DAY = 2;

    /** How many alternatives the swap dialog offers at once. */
    private static final int MAX_SWAP_OPTIONS = 4;

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
                                       "experienceId", "slotId", "placeName", "placeArea",
                                       "ticketed", "officialUrl"],
                          "properties": {
                            "startTimeLocal": {"type": "string", "description": "24h HH:mm local time"},
                            "kind": {"type": "string", "enum": ["EXPERIENCE", "FOOD", "SIGHT", "TIP"]},
                            "title": {"type": "string"},
                            "description": {"type": "string", "description": "One vivid sentence, max 18 words"},
                            "experienceId": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "slotId": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "placeName": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "placeArea": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                            "ticketed": {"type": "boolean", "description": "SIGHT only: true when entry needs a ticket/reservation"},
                            "officialUrl": {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Ticketed SIGHT only: the venue's own official website"}
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

    /** Output contract for a single-day refine: the day's new theme + its complete item list. */
    private static final String REFINE_OUTPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["theme", "items"],
              "properties": {
                "theme": {"type": "string", "description": "3-5 words"},
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["startTimeLocal", "kind", "title", "description",
                                 "experienceId", "slotId", "placeName", "placeArea",
                                 "ticketed", "officialUrl"],
                    "properties": {
                      "startTimeLocal": {"type": "string", "description": "24h HH:mm local time"},
                      "kind": {"type": "string", "enum": ["EXPERIENCE", "FOOD", "SIGHT", "TIP"]},
                      "title": {"type": "string"},
                      "description": {"type": "string", "description": "One vivid sentence, max 18 words"},
                      "experienceId": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                      "slotId": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                      "placeName": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                      "placeArea": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                      "ticketed": {"type": "boolean", "description": "SIGHT only: true when entry needs a ticket/reservation"},
                      "officialUrl": {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Ticketed SIGHT only: the venue's own official website"}
                    }
                  }
                }
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
    private final com.localbuddy.media.ExperiencePhotoRepository experiencePhotoRepository;
    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;
    private final int maxDays;
    private final int maxExperiencesInPrompt;
    private final int maxSlotsPerExperienceDay;
    private final int maxTokens;
    private final int maxMonthsAhead;

    public TripPlanService(
            TripPlanRepository tripPlanRepository,
            UserRepository userRepository,
            CityRepository cityRepository,
            AvailabilitySlotService availabilitySlotService,
            AvailabilitySlotRepository availabilitySlotRepository,
            BookingWindowPolicy bookingWindowPolicy,
            DealService dealService,
            com.localbuddy.media.ExperiencePhotoRepository experiencePhotoRepository,
            ClaudeClient claudeClient,
            ObjectMapper objectMapper,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.ai.trip-planner.max-days:7}") int maxDays,
            @Value("${app.ai.trip-planner.max-experiences-in-prompt:40}") int maxExperiencesInPrompt,
            @Value("${app.ai.trip-planner.max-slots-per-experience-day:4}") int maxSlotsPerExperienceDay,
            @Value("${app.ai.trip-planner.max-tokens:12000}") int maxTokens,
            @Value("${app.ai.trip-planner.max-months-ahead:6}") int maxMonthsAhead
    ) {
        this.tripPlanRepository = tripPlanRepository;
        this.userRepository = userRepository;
        this.cityRepository = cityRepository;
        this.availabilitySlotService = availabilitySlotService;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.bookingWindowPolicy = bookingWindowPolicy;
        this.dealService = dealService;
        this.experiencePhotoRepository = experiencePhotoRepository;
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        this.maxDays = maxDays;
        this.maxExperiencesInPrompt = maxExperiencesInPrompt;
        this.maxSlotsPerExperienceDay = maxSlotsPerExperienceDay;
        this.maxTokens = maxTokens;
        this.maxMonthsAhead = maxMonthsAhead;
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
        ZonedDateTime nowInCity = ZonedDateTime.now(zone);
        LocalDate today = nowInCity.toLocalDate();

        if (request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("End date cannot be before start date");
        }
        if (request.endDate().isBefore(today)) {
            throw new BadRequestException("Those dates have already passed in " + city.getName()
                    + " — please pick dates from today onward");
        }
        // Traveler timezones differ from the city's: someone planning "today" from New York in
        // the evening is already on the city's yesterday. Clamp the start to the city's today
        // (that IS "now" there) instead of rejecting the request.
        LocalDate startDate = request.startDate().isBefore(today) ? today : request.startDate();
        LocalDate endDate = request.endDate();
        if (startDate.isAfter(today.plusMonths(maxMonthsAhead))) {
            throw new BadRequestException(
                    "Trips can be planned up to " + maxMonthsAhead + " months ahead");
        }
        long dayCount = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (dayCount > maxDays) {
            throw new BadRequestException("A trip plan can cover at most " + maxDays + " days");
        }
        // The effective request (possibly clamped) drives everything below — prompt, inventory,
        // validation and persistence — so the plan and its dates always agree.
        CreateTripPlanRequest effective = new CreateTripPlanRequest(
                request.citySlug(), startDate, endDate, request.partySize(),
                request.interests(), request.notes(), request.privateTour(), request.language(),
                request.budget());

        // A trip starting today starts NOW, not at midnight: only offer slots that haven't
        // started yet, and tell the model what time it currently is in the city.
        boolean startsToday = startDate.equals(today);
        Instant from = startsToday ? nowInCity.toInstant() : startDate.atStartOfDay(zone).toInstant();
        Instant to = endDate.plusDays(1).atStartOfDay(zone).toInstant();
        String nowTimeLocal = startsToday ? TIME_FORMAT.format(nowInCity) : null;

        boolean privateTour = effective.isPrivateTour();

        // Real, bookable inventory only. Shared mode: seat-available, cutoff-open slots for the
        // party size. Private mode: empty slots of buyout-capable experiences (flat private price).
        List<AvailabilitySlot> slots = privateTour
                ? availabilitySlotService.getPrivateBuyoutSlotsForCityBetween(
                        city.getSlug(), from, to, effective.partySize())
                : availabilitySlotService.getBookableSlotsForCityBetween(
                        city.getSlug(), from, to, effective.partySize());
        Map<UUID, ExperienceInventory> inventory = groupInventory(slots, zone);

        List<DealResponse> deals = liveDealsForWindow(city, from);

        String language = effective.languageOrDefault();
        String userPrompt = buildUserPrompt(effective, city, zone, dayCount, inventory, deals, nowTimeLocal);
        String systemPrompt = buildSystemPrompt(dayCount, privateTour, language, effective.budget(), effective.partySize());

        JsonNode schema = readSchema(OUTPUT_SCHEMA_JSON);
        AiStructuredResult result = claudeClient.completeStructured(
                systemPrompt,
                List.of(AiMessage.user(userPrompt)),
                schema,
                // Thinking off: the whole max_tokens budget goes to the plan JSON (adaptive
                // thinking would share it and could truncate near the cap), and generation
                // gets the extended read timeout instead of blind retries.
                AiCallOptions.longGeneration(maxTokens)
        );

        TripPlanDocument document = assembleDocument(result.json(), effective, zone, inventory, city);

        TripPlan tripPlan = new TripPlan();
        tripPlan.setToken(generateToken());
        tripPlan.setCity(city);
        tripPlan.setUser(userRepository.getReferenceById(userId));
        tripPlan.setStartDate(startDate);
        tripPlan.setEndDate(endDate);
        tripPlan.setPartySize(effective.partySize());
        tripPlan.setPrivateTour(privateTour);
        tripPlan.setInterests(trimToNull(effective.interests()));
        tripPlan.setNotes(trimToNull(effective.notes()));
        tripPlan.setBudget(effective.budget());
        tripPlan.setStatus(TripPlanStatus.ACTIVE);
        tripPlan.setLanguage(language);
        tripPlan.setPlan(writeDocument(document));
        tripPlan.setModel(claudeClient.getModel());
        tripPlan.setInputTokens(result.inputTokens());
        tripPlan.setOutputTokens(result.outputTokens());
        tripPlan = tripPlanRepository.save(tripPlan);

        return toResponse(tripPlan, city, document, userId);
    }

    /** A saved plan with each bookable item's availability re-checked against live inventory. */
    @Transactional(readOnly = true)
    public TripPlanResponse getPlanByToken(String token) {
        return getPlanByToken(token, null);
    }

    /** As above, with the viewer resolved so the response can carry the {@code owned} flag. */
    @Transactional(readOnly = true)
    public TripPlanResponse getPlanByToken(String token, UUID viewerId) {
        TripPlan tripPlan = findViewablePlanByToken(token);

        TripPlanDocument document = readDocument(tripPlan.getPlan());
        TripPlanDocument refreshed = refreshAvailability(document, tripPlan.getPartySize(), tripPlan.isPrivateTour());

        return toResponse(tripPlan, tripPlan.getCity(), refreshed, viewerId);
    }

    /** Plan-page view (the share-token GET): count it, then serve the plan. */
    @Transactional
    public TripPlanResponse getPlanByTokenCountingView(String token, UUID viewerId) {
        TripPlanResponse response = getPlanByToken(token, viewerId);
        tripPlanRepository.bumpViewCount(token, Instant.now());
        return response;
    }

    /** Entity + parsed document for the bundle-checkout flow. */
    @Transactional(readOnly = true)
    public TripPlanWithDocument getPlanEntityByToken(String token) {
        TripPlan tripPlan = findActivePlanByToken(token);
        return new TripPlanWithDocument(tripPlan.getId(), tripPlan.getPartySize(),
                tripPlan.isPrivateTour(), readDocument(tripPlan.getPlan()));
    }

    /**
     * Booking-side lookups (checkout, swap, refine) require an ACTIVE plan; an archived
     * (past) trip is view-only.
     */
    private TripPlan findActivePlanByToken(String token) {
        return tripPlanRepository.findByToken(token)
                .filter(plan -> plan.getStatus() == TripPlanStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Trip plan not found"));
    }

    /** Viewing (page, PDF, calendar, feedback) works for active AND archived (past) trips. */
    private TripPlan findViewablePlanByToken(String token) {
        return tripPlanRepository.findByToken(token)
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
        TripPlan tripPlan = findViewablePlanByToken(token);
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
        ReplacementContext ctx = loadReplacementContext(userId, token, itemId);

        // Live re-check — the client saying "sold out" isn't trusted.
        AvailabilitySlot currentSlot = availabilitySlotRepository
                .findAllWithExperienceByIdIn(List.of(ctx.target().slotId()))
                .stream().findFirst().orElse(null);
        if (isSlotStillBookable(currentSlot, ctx.partySize(), ctx.privateTour(), Instant.now())) {
            throw new BadRequestException("This item is still bookable — no replacement needed");
        }

        List<AvailabilitySlot> candidates = replacementCandidates(ctx, true);

        // Prefer another time of the same experience, otherwise any other free experience.
        String intendedTime = ctx.target().startTimeLocal();
        AvailabilitySlot replacementSlot = pickClosestFreeSlot(
                candidates.stream()
                        .filter(slot -> slot.getExperience().getId().equals(ctx.target().experienceId()))
                        .toList(),
                Set.of(), intendedTime, ctx.zone());
        boolean sameExperience = replacementSlot != null;
        if (replacementSlot == null) {
            replacementSlot = pickClosestFreeSlot(candidates, Set.of(), intendedTime, ctx.zone());
        }
        if (replacementSlot == null) {
            throw new ResourceNotFoundException("No bookable alternative was found for that day");
        }

        String description = sameExperience
                ? ctx.target().description()
                : replacementNote(ctx.tripPlan().getLanguage()).formatted(ctx.target().title());
        return applyReplacement(ctx, replacementSlot, sameExperience, description, userId);
    }

    /**
     * Deliberate "swap this item" (owner only): replaces a bookable item with a DIFFERENT
     * experience on the same day. With {@code requestedSlotId} (the swap dialog's pick) that
     * exact candidate is used — validated against the live candidate set; without it the
     * closest-to-the-same-time candidate is auto-picked (older clients). A new time of the
     * same experience wouldn't change what the traveler asked to change, so the same
     * experience is never offered back.
     */
    @Transactional
    public TripPlanResponse swapItem(UUID userId, String token, String itemId, UUID requestedSlotId) {
        ReplacementContext ctx = loadReplacementContext(userId, token, itemId);

        List<AvailabilitySlot> candidates = replacementCandidates(ctx, false);
        AvailabilitySlot replacementSlot;
        if (requestedSlotId != null) {
            replacementSlot = candidates.stream()
                    .filter(slot -> slot.getId().equals(requestedSlotId))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "That option is no longer available — pick another one"));
        } else {
            replacementSlot = pickClosestFreeSlot(candidates, Set.of(), ctx.target().startTimeLocal(), ctx.zone());
            if (replacementSlot == null) {
                throw new ResourceNotFoundException("No bookable alternative was found for that day");
            }
        }

        String description = swapNote(ctx.tripPlan().getLanguage()).formatted(ctx.target().title());
        return applyReplacement(ctx, replacementSlot, false, description, userId);
    }

    /** Output contract for the swap-options AI ranking: candidate experience ids, best first. */
    private static final String SWAP_RANK_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["rankedExperienceIds"],
              "properties": {
                "rankedExperienceIds": {
                  "type": "array",
                  "items": {"type": "string", "description": "An experienceId copied EXACTLY from the candidate list"}
                }
              }
            }
            """;

    /**
     * The swap dialog's option list (owner only): up to {@value #MAX_SWAP_OPTIONS} DIFFERENT
     * experiences bookable on the item's day, each at its slot closest to the item's current
     * time, with the card fields (image, rating, price) resolved in one batch. With a free-text
     * {@code instruction} and a configured model, the candidates are AI-ranked to the
     * instruction first; otherwise ranked by host rating, then time closeness. Deliberately NOT
     * transactional — the optional model call must never run inside an open transaction (the
     * repository/service calls it makes carry their own).
     */
    public TripPlanSwapOptionsResponse swapOptions(UUID userId, String token, String itemId, String instruction) {
        ReplacementContext ctx = loadReplacementContext(userId, token, itemId);
        List<AvailabilitySlot> candidates = replacementCandidates(ctx, false);

        // One candidate per experience: its slot closest to the item's current time.
        Map<UUID, List<AvailabilitySlot>> byExperience = candidates.stream()
                .collect(Collectors.groupingBy(slot -> slot.getExperience().getId(),
                        LinkedHashMap::new, Collectors.toList()));
        List<AvailabilitySlot> bestPerExperience = new ArrayList<>();
        for (List<AvailabilitySlot> slots : byExperience.values()) {
            AvailabilitySlot pick = pickClosestFreeSlot(slots, Set.of(), ctx.target().startTimeLocal(), ctx.zone());
            if (pick != null) {
                bestPerExperience.add(pick);
            }
        }
        if (bestPerExperience.isEmpty()) {
            throw new ResourceNotFoundException("No bookable alternative was found for that day");
        }

        int intendedMinutes = parseMinutesOfDay(ctx.target().startTimeLocal());
        Comparator<AvailabilitySlot> defaultRank = Comparator
                .comparing((AvailabilitySlot slot) -> hostRating(slot.getExperience()),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(slot -> minutesFromIntended(slot, intendedMinutes, ctx.zone()));
        bestPerExperience.sort(defaultRank);

        String cleanInstruction = instruction == null ? "" : instruction.trim();
        boolean aiRefined = false;
        if (cleanInstruction.length() >= 3 && claudeClient.isConfigured()) {
            List<AvailabilitySlot> ranked = rankSwapCandidatesWithAi(ctx, bestPerExperience, cleanInstruction);
            if (ranked != null) {
                bestPerExperience = ranked;
                aiRefined = true;
            }
        }

        List<AvailabilitySlot> top = bestPerExperience.stream().limit(MAX_SWAP_OPTIONS).toList();

        // Cover images for the option cards, one batched query.
        List<UUID> experienceIds = top.stream().map(slot -> slot.getExperience().getId()).toList();
        Map<UUID, String> coverByExperienceId = new HashMap<>();
        for (com.localbuddy.media.ExperiencePhoto photo
                : experiencePhotoRepository.findCoverPhotosByExperienceIds(experienceIds)) {
            coverByExperienceId.putIfAbsent(photo.getExperience().getId(), photo.getUrl());
        }

        List<TripPlanSwapOption> options = top.stream()
                .map(slot -> {
                    Experience experience = slot.getExperience();
                    String about = notBlank(experience.getShortDescription())
                            ? experience.getShortDescription()
                            : experience.getDescription();
                    return new TripPlanSwapOption(
                            slot.getId(),
                            experience.getId(),
                            experience.getSlug(),
                            experience.getTitle(),
                            truncate(about == null ? null : about.replaceAll("\\s+", " ").trim(), 160),
                            experience.getCategory() != null ? experience.getCategory().getName() : null,
                            TIME_FORMAT.format(slot.getStartTime().atZone(ctx.zone())),
                            experience.getDurationMinutes(),
                            ctx.privateTour() ? experience.getPrivatePrice() : experience.getPriceAmount(),
                            experience.getCurrency(),
                            hostRating(experience),
                            experience.getLocalProfile() != null ? experience.getLocalProfile().getTotalReviews() : null,
                            coverByExperienceId.get(experience.getId()),
                            experience.getMeetingArea());
                })
                .toList();

        return new TripPlanSwapOptionsResponse(options, aiRefined);
    }

    /** Minutes between a slot's local start and the item's current time (for tie-breaking). */
    private int minutesFromIntended(AvailabilitySlot slot, int intendedMinutes, ZoneId zone) {
        int slotMinutes = slot.getStartTime().atZone(zone).toLocalTime().toSecondOfDay() / 60;
        return Math.abs(slotMinutes - (intendedMinutes == Integer.MAX_VALUE ? 12 * 60 : intendedMinutes));
    }

    /**
     * One snappy structured model call ranking the swap candidates to the traveler's
     * instruction. Returns null on any failure — the caller keeps the default ranking
     * (options must never break because the model hiccuped).
     */
    private List<AvailabilitySlot> rankSwapCandidatesWithAi(
            ReplacementContext ctx, List<AvailabilitySlot> candidates, String instruction) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("The traveler wants to replace this itinerary stop: \"")
                    .append(ctx.target().title()).append("\" at ").append(ctx.target().startTimeLocal())
                    .append(" in ").append(ctx.city().getName()).append(".\n");
            sb.append("THEIR INSTRUCTION for what they'd rather have: ").append(instruction).append("\n");
            if (ctx.privateTour()) {
                sb.append("The plan books private whole-group buyouts; listed prices are flat per group.\n");
            }
            sb.append("\nCANDIDATE EXPERIENCES (rank ALL of these, best match first):\n");
            for (AvailabilitySlot slot : candidates) {
                Experience experience = slot.getExperience();
                sb.append("- experienceId: ").append(experience.getId())
                        .append(" | title: ").append(experience.getTitle());
                if (experience.getCategory() != null) {
                    sb.append(" | category: ").append(experience.getCategory().getName());
                }
                BigDecimal price = ctx.privateTour() ? experience.getPrivatePrice() : experience.getPriceAmount();
                if (price != null) {
                    sb.append(" | price: ").append(price).append(" ").append(experience.getCurrency());
                }
                sb.append(" | starts: ").append(TIME_FORMAT.format(slot.getStartTime().atZone(ctx.zone())));
                String about = notBlank(experience.getShortDescription())
                        ? experience.getShortDescription()
                        : experience.getDescription();
                if (notBlank(about)) {
                    sb.append(" | about: ").append(truncate(about.replaceAll("\\s+", " ").trim(), 140));
                }
                sb.append("\n");
            }

            AiStructuredResult result = claudeClient.completeStructured(
                    "You rank replacement options for one stop of a traveler's itinerary. "
                            + "Order the candidate experiences by how well they satisfy the traveler's "
                            + "instruction (best first). Use ONLY experienceIds copied exactly from the "
                            + "candidate list; include every candidate exactly once.",
                    List.of(AiMessage.user(sb.toString())),
                    readSchema(SWAP_RANK_SCHEMA_JSON),
                    AiCallOptions.lowLatency(600)
            );

            // LinkedHashMap: leftovers the model forgot keep their default-ranked order.
            Map<UUID, AvailabilitySlot> byExperienceId = candidates.stream()
                    .collect(Collectors.toMap(slot -> slot.getExperience().getId(), Function.identity(),
                            (first, second) -> first, LinkedHashMap::new));
            List<AvailabilitySlot> ranked = new ArrayList<>();
            for (JsonNode idNode : result.json().path("rankedExperienceIds")) {
                UUID experienceId = parseUuid(idNode.asText(null));
                AvailabilitySlot slot = experienceId != null ? byExperienceId.remove(experienceId) : null;
                if (slot != null) {
                    ranked.add(slot);
                }
            }
            // Anything the model forgot keeps its default order at the tail.
            ranked.addAll(byExperienceId.values());
            return ranked.isEmpty() ? null : ranked;
        } catch (Exception ex) {
            log.warn("Swap-options AI ranking failed; using default ranking: {}", ex.getMessage());
            return null;
        }
    }

    /** Everything item replacement needs, loaded and owner-checked once. */
    private record ReplacementContext(
            TripPlan tripPlan,
            TripPlanDocument document,
            City city,
            ZoneId zone,
            int partySize,
            boolean privateTour,
            LocalDate date,
            TripPlanItem target,
            List<AvailabilitySlot> daySlots,
            Set<UUID> usedSlotIds,
            Set<UUID> plannedExperienceIds
    ) {
    }

    private ReplacementContext loadReplacementContext(UUID userId, String token, String itemId) {
        TripPlan tripPlan = findActivePlanByToken(token);
        // 404 (not 403) for non-owners so the endpoint doesn't confirm a token exists.
        if (tripPlan.getUser() == null || !tripPlan.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Trip plan not found");
        }

        TripPlanDocument document = readDocument(tripPlan.getPlan());
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

        City city = tripPlan.getCity();
        ZoneId zone = resolveZone(city);
        int partySize = tripPlan.getPartySize() != null ? tripPlan.getPartySize() : 1;
        boolean privateTour = tripPlan.isPrivateTour();

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

        return new ReplacementContext(tripPlan, document, city, zone, partySize, privateTour,
                date, target, daySlots, usedSlotIds, plannedExperienceIds);
    }

    /**
     * Free slots on the target's day that could stand in for it. The target's own experience
     * is included only when {@code allowSameExperience} (heal prefers it; swap forbids it);
     * other experiences already in the plan are never offered.
     */
    private List<AvailabilitySlot> replacementCandidates(ReplacementContext ctx, boolean allowSameExperience) {
        UUID targetExperienceId = ctx.target().experienceId();
        return ctx.daySlots().stream()
                .filter(slot -> !ctx.usedSlotIds().contains(slot.getId()))
                .filter(slot -> {
                    UUID experienceId = slot.getExperience().getId();
                    if (experienceId.equals(targetExperienceId)) {
                        return allowSameExperience;
                    }
                    return !ctx.plannedExperienceIds().contains(experienceId);
                })
                .toList();
    }

    /** Builds the replacement item, swaps it in (re-sorting that day), persists, responds. */
    private TripPlanResponse applyReplacement(ReplacementContext ctx, AvailabilitySlot replacementSlot,
                                              boolean sameExperience, String description, UUID userId) {
        Experience experience = replacementSlot.getExperience();
        String slotTimeLocal = TIME_FORMAT.format(replacementSlot.getStartTime().atZone(ctx.zone()));
        BigDecimal itemPrice = ctx.privateTour() ? experience.getPrivatePrice() : experience.getPriceAmount();

        TripPlanItem replacement = new TripPlanItem(
                ctx.target().id(), slotTimeLocal, "EXPERIENCE",
                sameExperience ? ctx.target().title() : experience.getTitle(),
                description,
                experience.getId(), experience.getSlug(), experience.getTitle(),
                frontendBaseUrl + "/experience/" + experience.getSlug(),
                buildBookingUrl(experience, ctx.date(), slotTimeLocal, ctx.partySize(), ctx.privateTour()),
                replacementSlot.getId(), replacementSlot.getStartTime(), replacementSlot.getEndTime(),
                itemPrice, null, experienceMapsUrl(experience), true, null, null, null);

        List<TripPlanDay> newDays = ctx.document().days().stream()
                .map(day -> {
                    List<TripPlanItem> items = new ArrayList<>(day.items().stream()
                            .map(item -> item.id().equals(ctx.target().id()) ? replacement : item)
                            .toList());
                    if (day.date().equals(ctx.date())) {
                        // The stand-in may run at a different time — keep the day chronological.
                        items.sort(Comparator.comparingInt(item -> parseMinutesOfDay(item.startTimeLocal())));
                    }
                    return day.withItems(List.copyOf(items));
                })
                .toList();
        TripPlanDocument updated =
                recomputeEstimatedTotal(ctx.document().withDays(newDays), ctx.partySize(), ctx.privateTour());

        TripPlan tripPlan = ctx.tripPlan();
        tripPlan.setPlan(writeDocument(updated));
        tripPlan = tripPlanRepository.save(tripPlan);

        return toResponse(tripPlan, ctx.city(),
                refreshAvailability(updated, ctx.partySize(), ctx.privateTour()), userId);
    }

    /** Note shown on a swapped-in item, in the plan's own language. */
    private String replacementNote(String language) {
        return switch (language == null ? "en" : language) {
            case "nl" -> "Vervanging voor \"%s\", dat niet meer beschikbaar is.";
            case "fr" -> "Remplacement de « %s », qui n'est plus disponible.";
            default -> "Replacement for \"%s\", which is no longer available.";
        };
    }

    /** Note shown on a deliberately swapped item, in the plan's own language. */
    private String swapNote(String language) {
        return switch (language == null ? "en" : language) {
            case "nl" -> "Gekozen in plaats van \"%s\".";
            case "fr" -> "Choisi à la place de « %s ».";
            default -> "Swapped in to replace \"%s\".";
        };
    }

    /**
     * AI-refines ONE day of the plan to the owner's instruction ("more food, slower morning"):
     * a single day-scoped model call whose result is validated and repaired against live
     * inventory exactly like initial generation. The other days are untouched. Deliberately
     * NOT transactional (like {@link #createPlan}): the model call must never run inside an
     * open database transaction — repository calls carry their own transactions, and only
     * already-initialized state is touched in between.
     */
    public TripPlanResponse refineDay(UUID userId, String token, LocalDate date, String instruction) {
        TripPlan tripPlan = findActivePlanByToken(token);
        // 404 (not 403) for non-owners so the endpoint doesn't confirm a token exists.
        if (tripPlan.getUser() == null || !tripPlan.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Trip plan not found");
        }
        String cleanInstruction = instruction == null ? "" : instruction.trim();
        if (cleanInstruction.length() < 3) {
            throw new BadRequestException("Tell the Genie what to change about this day");
        }

        TripPlanDocument document = readDocument(tripPlan.getPlan());
        int dayIndex = -1;
        for (int i = 0; i < document.days().size(); i++) {
            if (document.days().get(i).date().equals(date)) {
                dayIndex = i;
                break;
            }
        }
        if (dayIndex < 0) {
            throw new BadRequestException("This trip plan has no day on " + date);
        }
        TripPlanDay currentDay = document.days().get(dayIndex);

        // The plan's city is a lazy proxy and this method runs outside a transaction —
        // re-load it initialized (getId on a proxy never triggers initialization).
        City city = cityRepository.findById(tripPlan.getCity().getId())
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));
        ZoneId zone = resolveZone(city);
        int partySize = tripPlan.getPartySize() != null ? tripPlan.getPartySize() : 1;
        boolean privateTour = tripPlan.isPrivateTour();

        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<AvailabilitySlot> daySlots = privateTour
                ? availabilitySlotService.getPrivateBuyoutSlotsForCityBetween(city.getSlug(), from, to, partySize)
                : availabilitySlotService.getBookableSlotsForCityBetween(city.getSlug(), from, to, partySize);
        Map<UUID, ExperienceInventory> inventory = groupInventory(daySlots, zone);

        String language = tripPlan.getLanguage() == null ? "en" : tripPlan.getLanguage();
        String systemPrompt = buildRefineSystemPrompt(privateTour, language);
        String userPrompt = buildRefineUserPrompt(
                tripPlan, document, currentDay, city, zone, partySize, privateTour, cleanInstruction, inventory);

        AiStructuredResult result = claudeClient.completeStructured(
                systemPrompt,
                List.of(AiMessage.user(userPrompt)),
                readSchema(REFINE_OUTPUT_SCHEMA_JSON),
                // Day-scoped, so a fraction of a full plan — same thinking/timeout trade-offs.
                AiCallOptions.longGeneration(Math.min(maxTokens, 4000))
        );

        // Validate/repair the refined items exactly like initial assembly. Fresh "-r" item ids
        // stay unique against every day's "-i" ids across repeated refines. Slots of other days
        // are on other dates, so seeding usedSlotIds is belt-and-braces only.
        Set<UUID> usedSlotIds = document.days().stream()
                .filter(day -> !day.date().equals(date))
                .flatMap(day -> day.items().stream())
                .map(TripPlanItem::slotId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        JsonNode modelItems = result.json().path("items");
        List<TripPlanItem> items = new ArrayList<>();
        int experienceCount = 0;
        for (int i = 0; i < modelItems.size(); i++) {
            // Same two-experiences-per-day cap as initial generation.
            if (experienceCount >= MAX_EXPERIENCES_PER_DAY
                    && "EXPERIENCE".equals(modelItems.get(i).path("kind").asText(""))) {
                continue;
            }
            TripPlanItem item = assembleItem(modelItems.get(i),
                    "d" + (dayIndex + 1) + "-r" + (i + 1),
                    date, zone, inventory, usedSlotIds, partySize, privateTour, city);
            if (item != null) {
                items.add(item);
                if ("EXPERIENCE".equals(item.kind())) {
                    experienceCount++;
                }
            }
        }
        if (items.isEmpty()) {
            throw new ServiceUnavailableException(
                    "The Genie couldn't refine this day; please try a different instruction");
        }
        items.sort(Comparator.comparingInt(item -> parseMinutesOfDay(item.startTimeLocal())));

        String theme = truncate(result.json().path("theme").asText(currentDay.theme()), 160);
        List<TripPlanDay> newDays = new ArrayList<>(document.days());
        newDays.set(dayIndex, new TripPlanDay(date, theme, items));
        TripPlanDocument updated =
                recomputeEstimatedTotal(document.withDays(List.copyOf(newDays)), partySize, privateTour);

        tripPlan.setPlan(writeDocument(updated));
        tripPlan = tripPlanRepository.save(tripPlan);

        return toResponse(tripPlan, city, refreshAvailability(updated, partySize, privateTour), userId);
    }

    private String buildRefineSystemPrompt(boolean privateTour, String language) {
        return ("""
                You are the AI trip planner for LocalBuddy, a marketplace where travelers book small-group \
                experiences hosted by locals. You are REFINING ONE DAY of an existing itinerary to the \
                traveler's instruction — the other days are fixed and none of your concern.

                HARD RULES:
                - Return the COMPLETE refined day (theme + every item, typically 5-8), not a diff. Keep the \
                items the instruction doesn't ask to change — copy their kind, title, description, time, and \
                (for EXPERIENCE items) experienceId and slotId EXACTLY as given.
                - LocalBuddy experiences may ONLY come from the provided list, and ONLY at one of the listed \
                slot start times: set kind="EXPERIENCE" and copy the experienceId and the chosen slotId \
                EXACTLY as given. Never invent experiences, slots, or times; never reuse a slotId twice. \
                The refined day may contain at most TWO EXPERIENCE items.
                - Non-bookable items are suggestions: kind="FOOD" for cafes/restaurants/markets, kind="SIGHT" for \
                viewpoints/parks/landmarks/neighborhoods, kind="TIP" for practical advice. For FOOD and SIGHT give \
                a real, well-known placeName plus its neighborhood in placeArea; prefer beloved local spots over \
                tourist traps. Do not invent opening hours or prices. For these items experienceId and slotId must be null.
                - SIGHT tickets: set ticketed=true ONLY when the place genuinely requires a paid ticket or \
                reservation to enter (museums, towers, cruises, exhibitions) and then set officialUrl to that \
                venue's OWN official website (https, the venue's real domain — NEVER Google, Maps, TripAdvisor, \
                Wikipedia or any aggregator); free public places (parks, viewpoints, sunset spots) get \
                ticketed=false and officialUrl=null, as do all FOOD and TIP items. When unsure about the real \
                site, use null.
                - startTimeLocal is 24h "HH:mm" local time. Order items chronologically. No overlaps: respect each \
                experience's durationMinutes and leave at least 30 minutes of travel time between consecutive items.
                - BREVITY (the plan is skimmed on a phone): theme 3-5 words; each item description exactly ONE \
                vivid sentence of at most 18 words. No filler phrases — name the concrete thing that makes the \
                place worth it.
                - Apply the traveler's INSTRUCTION faithfully while keeping the day realistic for the city.
                """ + privateRuleText(privateTour) + languageRuleText(language));
    }

    private String buildRefineUserPrompt(
            TripPlan tripPlan,
            TripPlanDocument document,
            TripPlanDay day,
            City city,
            ZoneId zone,
            int partySize,
            boolean privateTour,
            String instruction,
            Map<UUID, ExperienceInventory> inventory
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CITY: ").append(city.getName()).append(", ").append(city.getCountry())
                .append(" (timezone ").append(zone).append(")\n");
        sb.append("TRIP: \"").append(document.title()).append("\" — refine ONLY the day of ")
                .append(day.date()).append("\n");
        sb.append("PARTY SIZE: ").append(partySize).append("\n");
        if (tripPlan.getBudget() != null) {
            sb.append("BUDGET: ").append(tripPlan.getBudget())
                    .append(" EUR total for the whole group across the whole trip — respect it when picking replacements\n");
        }
        if (privateTour) {
            sb.append("TOUR TYPE: PRIVATE — each experience is a whole-group private buyout\n");
        }
        if (notBlank(tripPlan.getInterests())) {
            sb.append("INTERESTS: ").append(tripPlan.getInterests().trim()).append("\n");
        }
        if (notBlank(tripPlan.getNotes())) {
            sb.append("NOTES: ").append(tripPlan.getNotes().trim()).append("\n");
        }
        sb.append("\nINSTRUCTION (what to change about this day): ").append(instruction).append("\n");

        sb.append("\nTHE DAY AS IT IS NOW");
        if (notBlank(day.theme())) {
            sb.append(" (theme: ").append(day.theme()).append(")");
        }
        sb.append(":\n");
        for (TripPlanItem item : day.items()) {
            sb.append("- ");
            if (notBlank(item.startTimeLocal())) {
                sb.append(item.startTimeLocal()).append(" ");
            }
            sb.append("[").append(item.kind()).append("] ").append(item.title());
            if (item.experienceId() != null) {
                sb.append(" (experienceId ").append(item.experienceId());
                if (item.slotId() != null) {
                    sb.append(", slotId ").append(item.slotId());
                }
                sb.append(")");
            }
            if (notBlank(item.description())) {
                sb.append(" — ").append(item.description());
            }
            sb.append("\n");
        }

        sb.append("\nBOOKABLE LOCALBUDDY EXPERIENCES FOR THIS DAY (the ONLY allowed EXPERIENCE items):\n");
        appendInventoryBlock(sb, inventory, privateTour, zone,
                "(none available this day — use FOOD/SIGHT/TIP items only)");

        return sb.toString();
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

    /** The private-tour prompt rule, shared by full-plan generation and day refine. */
    private String privateRuleText(boolean privateTour) {
        return privateTour
                ? """
                - PRIVATE TOURS: the traveler booked this trip as private tours — every listed \
                experience is reserved exclusively for their group (no other guests join), and the \
                listed privateTotalPrice is the flat price for the whole group, not per person. \
                Reflect this exclusivity naturally in your descriptions.
                """
                : "";
    }

    /** The output-language prompt rule, shared by full-plan generation and day refine. */
    private String languageRuleText(String language) {
        String languageName = switch (language) {
            case "nl" -> "Dutch";
            case "fr" -> "French";
            default -> "English";
        };
        return "en".equals(language) ? "" :
                "- LANGUAGE: write every human-readable text (title, summary, day themes, descriptions, "
                        + "FOOD/SIGHT/TIP titles, tips) in " + languageName + ". Keep proper nouns and place "
                        + "names in their local form, and copy LocalBuddy experience titles EXACTLY as given — "
                        + "never translate them.\n";
    }

    /**
     * The budget prompt rule: fit the whole-group experience total inside the traveler's
     * number, overshoot by at most 50% when a good plan demands it, and — when the number
     * is plainly unrealistic — drop the cap, build the cheapest genuinely good plan and
     * say so honestly in the summary. Empty when no budget was given.
     */
    private String budgetRuleText(Integer budget, int partySize) {
        if (budget == null) {
            return "";
        }
        return """
                - BUDGET: the traveler set a budget of %d EUR TOTAL for the whole group across the whole \
                trip. The combined price of your EXPERIENCE items (per-guest price x %d guests for shared \
                items; the flat group price for private buyouts) should fit inside it — prefer fewer or \
                cheaper experiences over blowing past it, and fill the gaps with great free FOOD/SIGHT \
                moments. If no good plan fits, exceed the budget by AT MOST 50%%, never more. If the \
                budget is far too low for that (shared experiences average roughly 50 EUR per person per \
                day), ignore the 50%% rule: build the cheapest genuinely good plan — even a single \
                affordable highlight with free wanders around it — and acknowledge the tight budget in \
                one warm, honest sentence inside the summary. Never pad a plan just to use budget up.
                """.formatted(budget, partySize);
    }

    private String buildSystemPrompt(long dayCount, boolean privateTour, String language, Integer budget, int partySize) {
        String privateRule = privateRuleText(privateTour);
        String languageRule = languageRuleText(language);
        String budgetRule = budgetRuleText(budget, partySize);
        return ("""
                You are the AI trip planner for LocalBuddy, a marketplace where travelers book small-group \
                experiences hosted by locals. You design warm, realistic, city-local itineraries.

                HARD RULES:
                - Return exactly %d day(s), using the exact dates provided, in order.
                - LocalBuddy experiences may ONLY come from the provided list, and ONLY at one of the listed \
                slot start times for that same date: set kind="EXPERIENCE" and copy the experienceId and the \
                chosen slotId EXACTLY as given. Never invent experiences, slots, or times; never reuse a slotId twice.
                - Weave 1-2 LocalBuddy experiences into each day when available, at their real slot times — \
                NEVER more than TWO experiences on the same day. They are the highlights of the trip — \
                schedule the rest of the day around them.
                - Non-bookable items are suggestions: kind="FOOD" for cafes/restaurants/markets, kind="SIGHT" for \
                viewpoints/parks/landmarks/neighborhoods (including a sunset spot), kind="TIP" for practical advice. \
                For FOOD and SIGHT give a real, well-known placeName plus its neighborhood in placeArea; prefer \
                beloved local spots over tourist traps. Do not invent opening hours or prices. \
                For these items experienceId and slotId must be null.
                - SIGHT tickets: set ticketed=true ONLY when the place genuinely requires a paid ticket or \
                reservation to enter (museums, towers, cruises, exhibitions, attractions) and then set officialUrl \
                to that venue's OWN official website (https, the venue's real domain — NEVER Google, Maps, \
                TripAdvisor, Wikipedia or any aggregator). Free public places — parks, squares, viewpoints, \
                sunset spots, neighborhoods, markets — get ticketed=false and officialUrl=null. Only give an \
                officialUrl you are confident is the venue's real site; when unsure use null. For FOOD and TIP \
                items ticketed=false and officialUrl=null always.
                - Structure each day roughly: breakfast (FOOD), a morning activity, lunch (FOOD), an afternoon \
                activity, a late-afternoon/sunset moment (SIGHT), and an evening plan (FOOD or an evening EXPERIENCE). \
                If the first day is marked as already underway, start it from the given current time instead — \
                never schedule anything before it.
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
                """ + budgetRule + privateRule + languageRule).formatted(dayCount);
    }

    private String buildUserPrompt(
            CreateTripPlanRequest request,
            City city,
            ZoneId zone,
            long dayCount,
            Map<UUID, ExperienceInventory> inventory,
            List<DealResponse> deals,
            /** Current local time (HH:mm) when the trip starts today; null otherwise. */
            String nowTimeLocal
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CITY: ").append(city.getName()).append(", ").append(city.getCountry())
                .append(" (timezone ").append(zone).append(")\n");
        sb.append("DATES: ").append(request.startDate()).append(" to ").append(request.endDate())
                .append(" (").append(dayCount).append(" day(s))\n");
        if (nowTimeLocal != null) {
            sb.append("FIRST DAY ALREADY UNDERWAY: it is now ").append(nowTimeLocal)
                    .append(" local time on ").append(request.startDate())
                    .append(". Schedule the first day ONLY from about 45 minutes after this time onward — ")
                    .append("skip breakfast/lunch/etc. that have already passed; a shorter first day is expected.\n");
        }
        sb.append("PARTY SIZE: ").append(request.partySize()).append("\n");
        if (request.budget() != null) {
            sb.append("BUDGET: ").append(request.budget())
                    .append(" EUR total for the whole group across the whole trip\n");
        }
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
        appendInventoryBlock(sb, inventory, request.isPrivateTour(), zone, "(none available for these dates)");

        return sb.toString();
    }

    /** The experience/slot listing shared by the full-plan and day-refine prompts. */
    private void appendInventoryBlock(StringBuilder sb, Map<UUID, ExperienceInventory> inventory,
                                      boolean privateTour, ZoneId zone, String emptyLine) {
        if (inventory.isEmpty()) {
            sb.append(emptyLine).append("\n");
        }
        for (ExperienceInventory entry : inventory.values()) {
            Experience experience = entry.experience();
            sb.append("\n* experienceId: ").append(experience.getId()).append("\n");
            sb.append("  title: ").append(experience.getTitle()).append("\n");
            if (experience.getCategory() != null) {
                sb.append("  category: ").append(experience.getCategory().getName()).append("\n");
            }
            sb.append("  durationMinutes: ").append(experience.getDurationMinutes()).append("\n");
            if (privateTour) {
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
            int experienceCount = 0;
            if (modelDay != null) {
                JsonNode modelItems = modelDay.path("items");
                for (int itemIndex = 0; itemIndex < modelItems.size(); itemIndex++) {
                    // Hard product rule: at most two experiences per day — the prompt asks for
                    // it, this enforces it even when the model overshoots.
                    if (experienceCount >= MAX_EXPERIENCES_PER_DAY
                            && "EXPERIENCE".equals(modelItems.get(itemIndex).path("kind").asText(""))) {
                        continue;
                    }
                    TripPlanItem item = assembleItem(
                            modelItems.get(itemIndex),
                            "d" + (dayIndex + 1) + "-i" + (itemIndex + 1),
                            date, zone, inventory, usedSlotIds, request.partySize(),
                            request.isPrivateTour(), city);
                    if (item != null) {
                        items.add(item);
                        if ("EXPERIENCE".equals(item.kind())) {
                            experienceCount++;
                        }
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
            // Only SIGHT items can be ticketed attractions; their official link must survive
            // sanitation (https, real host, no search/aggregator domains) or it's dropped.
            boolean ticketed = "SIGHT".equals(kind) && modelItem.path("ticketed").asBoolean(false);
            String officialUrl = ticketed ? sanitizeOfficialUrl(textOrNull(modelItem, "officialUrl")) : null;
            return new TripPlanItem(itemId, startTimeLocal, kind, title, description,
                    null, null, null, null, null, null, null, null, null,
                    placeName, mapsUrl, false, null, ticketed, officialUrl);
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
                    null, experienceMapsUrl(experience), false, null, null, null);
        }

        usedSlotIds.add(slot.getId());
        String slotTimeLocal = TIME_FORMAT.format(slot.getStartTime().atZone(zone));
        String bookingUrl = buildBookingUrl(experience, date, slotTimeLocal, partySize, privateTour);

        return new TripPlanItem(itemId, slotTimeLocal, kind, title, description,
                experience.getId(), experience.getSlug(), experience.getTitle(), experienceUrl,
                bookingUrl, slot.getId(), slot.getStartTime(), slot.getEndTime(),
                itemPrice, null, experienceMapsUrl(experience), true, null, null, null);
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

    private TripPlanResponse toResponse(TripPlan tripPlan, City city, TripPlanDocument document, UUID viewerId) {
        // Must match the Angular route exactly: /trip-planner/:token (no extra path segment).
        String shareUrl = frontendBaseUrl + "/trip-planner/" + tripPlan.getToken();
        List<String> bookableItemIds = document.days().stream()
                .flatMap(day -> day.items().stream())
                .filter(TripPlanItem::bookable)
                .map(TripPlanItem::id)
                .toList();
        boolean owned = viewerId != null
                && tripPlan.getUser() != null
                && viewerId.equals(tripPlan.getUser().getId());
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
                tripPlan.getCreatedAt(),
                tripPlan.getStatus() == TripPlanStatus.ARCHIVED,
                owned,
                tripPlan.getBudget()
        );
    }

    private ZoneId resolveZone(City city) {
        try {
            return ZoneId.of(city.getTimezone());
        } catch (Exception ex) {
            return ZoneId.of("Europe/Amsterdam");
        }
    }

    private JsonNode readSchema(String schemaJson) {
        try {
            return objectMapper.readTree(schemaJson);
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

    /** Hosts that are never a venue's own site — search engines, maps, aggregators, socials. */
    private static final List<String> BLOCKED_OFFICIAL_HOSTS = List.of(
            "google.", "goo.gl", "maps.app", "tripadvisor", "wikipedia", "wikivoyage",
            "facebook", "instagram", "yelp", "getyourguide", "viator", "booking.com",
            "expedia", "airbnb", "tiqets", "klook");

    /**
     * Keeps a model-suggested official-venue link only when it plausibly IS one:
     * https, a real host, not a search/aggregator/social domain, sane length.
     */
    private String sanitizeOfficialUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 300) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(rawUrl.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return null;
            }
            String lowerHost = host.toLowerCase(java.util.Locale.ROOT);
            for (String blocked : BLOCKED_OFFICIAL_HOSTS) {
                if (lowerHost.contains(blocked)) {
                    return null;
                }
            }
            return uri.toString();
        } catch (Exception ex) {
            return null;
        }
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
