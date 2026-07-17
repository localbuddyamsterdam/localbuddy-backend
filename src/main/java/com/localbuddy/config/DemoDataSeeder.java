package com.localbuddy.config;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.experience.BookingMode;
import com.localbuddy.experience.City;
import com.localbuddy.experience.CityRepository;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceCategory;
import com.localbuddy.experience.ExperienceCategoryRepository;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.experience.PriceInputMode;
import com.localbuddy.experience.TransportMode;
import com.localbuddy.localprofile.Gender;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.localprofile.LocalVerificationStatus;
import com.localbuddy.media.ExperiencePhoto;
import com.localbuddy.media.ExperiencePhotoRepository;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds realistic demo content (locals, experiences, photos and future availability)
 * for Amsterdam and Paris so the catalogue can be demoed end-to-end.
 *
 * <p>Runs only under the {@code demo} Spring profile (activate with
 * {@code SPRING_PROFILES_ACTIVE=dev,demo}). It is fully idempotent: users are keyed
 * by email and experiences by slug, so re-running never duplicates rows and can be
 * used to backfill anything that is missing.
 *
 * <p>All demo local accounts use the email domain {@code @localbuddy.demo} and share
 * the password {@code Password@123}; this makes them trivial to clean up
 * ({@code DELETE FROM users WHERE email LIKE '%@localbuddy.demo'} cascades to their
 * profiles, experiences, photos and slots).
 */
@Component
@Profile("demo")
@Order(20) // deterministic ordering; independent of DevAdminBootstrap
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String DEMO_EMAIL_DOMAIN = "@localbuddy.demo";
    private static final String DEMO_PASSWORD = "Password@123";
    /** How many days ahead to generate availability slots. */
    private static final int AVAILABILITY_HORIZON_DAYS = 28;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LocalProfileRepository localProfileRepository;
    private final CityRepository cityRepository;
    private final ExperienceCategoryRepository categoryRepository;
    private final ExperienceRepository experienceRepository;
    private final ExperiencePhotoRepository photoRepository;
    private final AvailabilitySlotRepository slotRepository;

    public DemoDataSeeder(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          LocalProfileRepository localProfileRepository,
                          CityRepository cityRepository,
                          ExperienceCategoryRepository categoryRepository,
                          ExperienceRepository experienceRepository,
                          ExperiencePhotoRepository photoRepository,
                          AvailabilitySlotRepository slotRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.localProfileRepository = localProfileRepository;
        this.cityRepository = cityRepository;
        this.categoryRepository = categoryRepository;
        this.experienceRepository = experienceRepository;
        this.photoRepository = photoRepository;
        this.slotRepository = slotRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, City> cities = ensureCities();
        Map<String, ExperienceCategory> categories = loadCategories();

        Map<String, LocalProfile> profilesByEmail = new HashMap<>();
        int newLocals = 0;
        for (LocalSpec spec : buildLocals()) {
            UpsertResult<LocalProfile> result = upsertLocal(spec, cities, categories);
            profilesByEmail.put(spec.email, result.entity);
            if (result.created) {
                newLocals++;
            }
        }

        int newExperiences = 0;
        int newSlots = 0;
        for (ExpSpec spec : buildExperiences()) {
            LocalProfile host = profilesByEmail.get(spec.localEmail);
            City city = cities.get(spec.citySlug);
            if (host == null || city == null) {
                log.warn("Skipping demo experience '{}' — missing host or city", spec.slug);
                continue;
            }
            if (experienceRepository.existsBySlug(spec.slug)) {
                continue;
            }
            newExperiences += 1;
            newSlots += createExperience(spec, host, city, categories);
        }

        log.info("Demo data seeding complete: {} new local(s), {} new experience(s), {} new availability slot(s). "
                        + "Total demo experiences now published: {}.",
                newLocals, newExperiences, newSlots,
                experienceRepository.countByStatus(ExperienceStatus.APPROVED));
    }

    // ---------------------------------------------------------------------
    // Reference data
    // ---------------------------------------------------------------------

    private Map<String, City> ensureCities() {
        Map<String, City> map = new HashMap<>();

        // Amsterdam already exists from the baseline migration; fetch it.
        cityRepository.findBySlug("amsterdam").ifPresent(c -> map.put("amsterdam", c));

        // Paris is not a launch city, so seed it here (demo profile only).
        City paris = cityRepository.findBySlug("paris").orElseGet(() -> {
            City c = new City();
            c.setName("Paris");
            c.setSlug("paris");
            c.setCountry("France");
            c.setLatitude(new BigDecimal("48.856600"));
            c.setLongitude(new BigDecimal("2.352200"));
            c.setTimezone("Europe/Paris");
            c.setActive(true);
            c.setDisplayOrder(20);
            return cityRepository.save(c);
        });
        map.put("paris", paris);
        return map;
    }

    private Map<String, ExperienceCategory> loadCategories() {
        Map<String, ExperienceCategory> map = new HashMap<>();
        for (ExperienceCategory c : categoryRepository.findAll()) {
            map.put(c.getSlug(), c);
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // Locals
    // ---------------------------------------------------------------------

    private UpsertResult<LocalProfile> upsertLocal(LocalSpec spec,
                                                   Map<String, City> cities,
                                                   Map<String, ExperienceCategory> categories) {
        String email = spec.email.toLowerCase();

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setFirstName(spec.firstName);
            u.setLastName(spec.lastName);
            u.setPreferredName(spec.preferredName);
            u.setEmail(email);
            u.setPhone(spec.phone);
            u.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
            u.setRole(UserRole.LOCAL);
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerified(true);
            u.setPhoneVerified(true);
            u.setMustChangePassword(false);
            u.setAvatarUrl(avatar(spec.avatarImg));
            u.setLanguages(String.join(", ", spec.languages));
            u.setRatingAvg(spec.ratingAvg);
            u.setTotalReviews(spec.totalReviews);
            return userRepository.save(u);
        });

        if (localProfileRepository.existsByUserId(user.getId())) {
            return localProfileRepository.findByUserId(user.getId())
                    .map(lp -> new UpsertResult<>(lp, false))
                    .orElseThrow();
        }

        LocalProfile lp = new LocalProfile();
        lp.setUser(user);
        lp.setDisplayName(spec.preferredName + " " + Character.toUpperCase(spec.lastName.charAt(0)) + ".");
        lp.setBio(spec.bio);
        lp.setPhoneNumber(spec.phone);
        lp.setHostCity(spec.hostCity);
        lp.setZipCode(spec.zip);
        lp.setCountry(spec.country);
        lp.setExperienceLanguages(new ArrayList<>(spec.languages));
        lp.setMotivation(spec.motivation);
        lp.setExperienceInfo(spec.experienceInfo);
        lp.setProfilePhotoUrl(avatar(spec.avatarImg));
        lp.setLegalFirstName(spec.firstName);
        lp.setLegalLastName(spec.lastName);
        lp.setPreferredName(spec.preferredName);
        lp.setCurrentAddress(spec.address);
        lp.setGender(spec.gender);
        lp.setVerificationStatus(LocalVerificationStatus.ID_VERIFIED);
        lp.setApprovalStatus(LocalApprovalStatus.APPROVED);
        lp.setVerificationProvider("demo-seed");
        lp.setVerificationStartedAt(Instant.now().minus(Duration.ofDays(30)));
        lp.setVerificationCompletedAt(Instant.now().minus(Duration.ofDays(28)));
        lp.setReviewedAt(Instant.now().minus(Duration.ofDays(27)));
        lp.setSubmittedAt(Instant.now().minus(Duration.ofDays(31)));
        lp.setTaxCountry(spec.hostCity.equals("Paris") ? "FR" : "NL");
        lp.setVatRegistered(false);
        lp.setPayoutsEnabled(true);
        lp.setRatingAvg(spec.ratingAvg);
        lp.setTotalReviews(spec.totalReviews);

        City city = cities.get(spec.citySlug);
        if (city != null) {
            lp.setExperienceCities(new ArrayList<>(List.of(city)));
        }
        List<ExperienceCategory> cats = new ArrayList<>();
        for (String slug : spec.categorySlugs) {
            ExperienceCategory cat = categories.get(slug);
            if (cat != null) {
                cats.add(cat);
            }
        }
        lp.setExperienceCategories(cats);

        return new UpsertResult<>(localProfileRepository.save(lp), true);
    }

    // ---------------------------------------------------------------------
    // Experiences + photos + availability
    // ---------------------------------------------------------------------

    /** @return number of availability slots created for this experience. */
    private int createExperience(ExpSpec spec,
                                 LocalProfile host,
                                 City city,
                                 Map<String, ExperienceCategory> categories) {
        Experience e = new Experience();
        e.setLocalProfile(host);
        e.setCity(city);

        ExperienceCategory primary = categories.get(spec.primaryCategory);
        e.setCategory(primary);
        Set<ExperienceCategory> catSet = new LinkedHashSet<>();
        if (primary != null) {
            catSet.add(primary);
        }
        for (String slug : spec.secondaryCategories) {
            ExperienceCategory c = categories.get(slug);
            if (c != null) {
                catSet.add(c);
            }
        }
        e.setCategories(catSet);

        e.setTitle(spec.title);
        e.setSlug(spec.slug);
        e.setShortDescription(spec.shortDescription);
        e.setDescription(spec.description);
        e.setMeetingArea(spec.meetingArea);
        e.setEndLocation(spec.endLocation);
        e.setTransportMode(spec.transportMode);
        e.setInclusions(spec.inclusions);
        e.setExclusions(spec.exclusions);
        e.setReasonsToBook(spec.reasonsToBook);
        e.setSafetyNotes(spec.safetyNotes);
        e.setMinimumAge(spec.minimumAge);
        e.setDurationMinutes(spec.durationMinutes);
        e.setPriceAmount(spec.price);
        e.setCurrency("EUR");
        e.setPriceInputMode(PriceInputMode.GROSS);
        e.setMaxGuests(spec.maxGuests);
        e.setBookingMode(spec.bookingMode);
        if (spec.bookingMode != BookingMode.SHARED) {
            e.setPrivatePrice(spec.privatePrice);
        }
        e.setLatitude(spec.latitude);
        e.setLongitude(spec.longitude);
        e.setStatus(ExperienceStatus.APPROVED);

        Experience saved = experienceRepository.save(e);

        int order = 0;
        for (PhotoSpec ps : spec.photos) {
            ExperiencePhoto photo = new ExperiencePhoto();
            photo.setExperience(saved);
            photo.setUrl(image(ps.unsplashId));
            photo.setCaption(ps.caption);
            photo.setContentType("image/jpeg");
            photo.setSortOrder(order);
            photo.setCover(order == 0);
            order++;
            photoRepository.save(photo);
        }

        return generateAvailability(spec, saved, host);
    }

    private int generateAvailability(ExpSpec spec, Experience experience, LocalProfile host) {
        ZoneId zone = ZoneId.of(spec.citySlug.equals("paris") ? "Europe/Paris" : "Europe/Amsterdam");
        Instant threshold = Instant.now().plus(Duration.ofHours(12));
        LocalDate start = LocalDate.now(zone).plusDays(1);

        List<AvailabilitySlot> slots = new ArrayList<>();
        for (int d = 0; d < AVAILABILITY_HORIZON_DAYS; d++) {
            LocalDate date = start.plusDays(d);
            for (SlotSpec ss : spec.slots) {
                if (date.getDayOfWeek() != ss.day) {
                    continue;
                }
                ZonedDateTime startZdt = date.atTime(ss.hour, ss.minute).atZone(zone);
                Instant startInstant = startZdt.toInstant();
                if (!startInstant.isAfter(threshold)) {
                    continue;
                }
                AvailabilitySlot slot = new AvailabilitySlot();
                slot.setExperience(experience);
                slot.setLocalProfile(host);
                slot.setStartTime(startInstant);
                slot.setEndTime(startInstant.plus(Duration.ofMinutes(spec.durationMinutes)));
                slot.setCapacity(spec.maxGuests);
                slot.setBookedCount(0);
                slot.setStatus(AvailabilityStatus.AVAILABLE);
                slots.add(slot);
            }
        }
        slotRepository.saveAll(slots);
        return slots.size();
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private static String image(String unsplashId) {
        return "https://images.unsplash.com/photo-" + unsplashId + "?auto=format&fit=crop&w=1600&q=80";
    }

    private static String avatar(String pravatarId) {
        return "https://i.pravatar.cc/300?img=" + pravatarId;
    }

    /** Join paragraphs with a blank line between them. */
    private static String p(String... paragraphs) {
        return String.join("\n\n", paragraphs);
    }

    /** Render a bulleted list. */
    private static String bullets(String... items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("• ").append(items[i]);
        }
        return sb.toString();
    }

    private static PhotoSpec photo(String unsplashId, String caption) {
        return new PhotoSpec(unsplashId, caption);
    }

    private static SlotSpec slot(DayOfWeek day, int hour, int minute) {
        return new SlotSpec(day, hour, minute);
    }

    private record UpsertResult<T>(T entity, boolean created) {
    }

    private record PhotoSpec(String unsplashId, String caption) {
    }

    private record SlotSpec(DayOfWeek day, int hour, int minute) {
    }

    // ---------------------------------------------------------------------
    // Fluent spec builders (kept package-private-simple for readability)
    // ---------------------------------------------------------------------

    private static final class LocalSpec {
        String email;
        String firstName;
        String lastName;
        String preferredName;
        String phone;
        String citySlug;
        String hostCity;
        String zip;
        String country;
        String address;
        String avatarImg;
        Gender gender;
        List<String> languages = new ArrayList<>();
        List<String> categorySlugs = new ArrayList<>();
        String bio;
        String motivation;
        String experienceInfo;
        BigDecimal ratingAvg = BigDecimal.ZERO;
        int totalReviews;

        LocalSpec id(String email, String first, String last, String preferred, Gender gender, String avatarImg) {
            this.email = email;
            this.firstName = first;
            this.lastName = last;
            this.preferredName = preferred;
            this.gender = gender;
            this.avatarImg = avatarImg;
            return this;
        }

        LocalSpec at(String citySlug, String hostCity, String zip, String country, String address) {
            this.citySlug = citySlug;
            this.hostCity = hostCity;
            this.zip = zip;
            this.country = country;
            this.address = address;
            return this;
        }

        LocalSpec phone(String phone) {
            this.phone = phone;
            return this;
        }

        LocalSpec languages(String... langs) {
            this.languages = new ArrayList<>(List.of(langs));
            return this;
        }

        LocalSpec offers(String... categorySlugs) {
            this.categorySlugs = new ArrayList<>(List.of(categorySlugs));
            return this;
        }

        LocalSpec bio(String bio) {
            this.bio = bio;
            return this;
        }

        LocalSpec motivation(String motivation) {
            this.motivation = motivation;
            return this;
        }

        LocalSpec experienceInfo(String experienceInfo) {
            this.experienceInfo = experienceInfo;
            return this;
        }

        LocalSpec rating(String avg, int reviews) {
            this.ratingAvg = new BigDecimal(avg);
            this.totalReviews = reviews;
            return this;
        }
    }

    private static final class ExpSpec {
        String localEmail;
        String citySlug;
        String primaryCategory;
        List<String> secondaryCategories = new ArrayList<>();
        String title;
        String slug;
        String shortDescription;
        String description;
        String meetingArea;
        String endLocation;
        TransportMode transportMode = TransportMode.WALKING;
        String inclusions;
        String exclusions;
        String reasonsToBook;
        String safetyNotes;
        int minimumAge;
        int durationMinutes;
        BigDecimal price;
        int maxGuests;
        BookingMode bookingMode = BookingMode.SHARED;
        BigDecimal privatePrice;
        BigDecimal latitude;
        BigDecimal longitude;
        List<PhotoSpec> photos = new ArrayList<>();
        List<SlotSpec> slots = new ArrayList<>();

        ExpSpec host(String email) {
            this.localEmail = email;
            return this;
        }

        ExpSpec city(String citySlug) {
            this.citySlug = citySlug;
            return this;
        }

        ExpSpec category(String primary, String... secondary) {
            this.primaryCategory = primary;
            this.secondaryCategories = new ArrayList<>(List.of(secondary));
            return this;
        }

        ExpSpec title(String title, String slug) {
            this.title = title;
            this.slug = slug;
            return this;
        }

        ExpSpec shortDescription(String s) {
            this.shortDescription = s;
            return this;
        }

        ExpSpec description(String s) {
            this.description = s;
            return this;
        }

        ExpSpec route(String meetingArea, String endLocation, TransportMode mode) {
            this.meetingArea = meetingArea;
            this.endLocation = endLocation;
            this.transportMode = mode;
            return this;
        }

        ExpSpec inclusions(String s) {
            this.inclusions = s;
            return this;
        }

        ExpSpec exclusions(String s) {
            this.exclusions = s;
            return this;
        }

        ExpSpec reasonsToBook(String s) {
            this.reasonsToBook = s;
            return this;
        }

        ExpSpec safetyNotes(String s) {
            this.safetyNotes = s;
            return this;
        }

        ExpSpec details(int minimumAge, int durationMinutes, String price, int maxGuests) {
            this.minimumAge = minimumAge;
            this.durationMinutes = durationMinutes;
            this.price = new BigDecimal(price);
            this.maxGuests = maxGuests;
            return this;
        }

        ExpSpec privateBuyout(String privatePrice) {
            this.bookingMode = BookingMode.PRIVATE_ALLOWED;
            this.privatePrice = new BigDecimal(privatePrice);
            return this;
        }

        ExpSpec geo(String lat, String lng) {
            this.latitude = new BigDecimal(lat);
            this.longitude = new BigDecimal(lng);
            return this;
        }

        ExpSpec photos(PhotoSpec... photos) {
            this.photos = new ArrayList<>(List.of(photos));
            return this;
        }

        ExpSpec slots(SlotSpec... slots) {
            this.slots = new ArrayList<>(List.of(slots));
            return this;
        }
    }

    // ---------------------------------------------------------------------
    // The actual demo content
    // ---------------------------------------------------------------------

    private List<LocalSpec> buildLocals() {
        List<LocalSpec> locals = new ArrayList<>();

        // ---- Amsterdam ----
        locals.add(new LocalSpec()
                .id("sanne.devries@localbuddy.demo", "Sanne", "de Vries", "Sanne", Gender.FEMALE, "45")
                .at("amsterdam", "Amsterdam", "1015 KV", "Netherlands", "Prinsengracht 210, 1015 KV Amsterdam")
                .phone("+31 6 21 44 87 90")
                .languages("Dutch", "English", "German")
                .offers("food", "local-markets")
                .rating("4.9", 128)
                .bio("Born and raised in the Jordaan, I grew up between the cheese shops on Westerstraat and my "
                        + "grandmother's apple-pie kitchen. I've spent ten years showing friends-of-friends where "
                        + "Amsterdammers actually eat — never the tourist traps on the main canals.")
                .motivation("I want visitors to taste the real, everyday Amsterdam: raw herring at a street stall, "
                        + "a genever with the regulars, and the small family bakeries that have survived the crowds.")
                .experienceInfo("10+ years hosting food walks; former line cook; certified for allergen handling."));

        locals.add(new LocalSpec()
                .id("daan.bakker@localbuddy.demo", "Daan", "Bakker", "Daan", Gender.MALE, "12")
                .at("amsterdam", "Amsterdam", "1054 ES", "Netherlands", "Bilderdijkstraat 44, 1054 ES Amsterdam")
                .phone("+31 6 18 90 33 21")
                .languages("Dutch", "English")
                .offers("photo-walk", "hidden-gems")
                .rating("4.8", 74)
                .bio("Amsterdam-based street and travel photographer. I know exactly which bridge catches the light "
                        + "at golden hour and which reflections show up only after the tour boats stop running.")
                .motivation("Everyone leaves Amsterdam with the same five postcard photos. I'd rather teach you to "
                        + "see the city — composition, light, patience — and take home images that feel like yours.")
                .experienceInfo("Professional photographer, 8 years. Workshops for all levels; phone or camera welcome."));

        locals.add(new LocalSpec()
                .id("pieter.janssen@localbuddy.demo", "Pieter", "Janssen", "Piet", Gender.MALE, "51")
                .at("amsterdam", "Amsterdam", "1017 RP", "Netherlands", "Reguliersgracht 60, 1017 RP Amsterdam")
                .phone("+31 6 44 12 76 05")
                .languages("Dutch", "English")
                .offers("nightlife", "cafe-hopping")
                .rating("4.7", 96)
                .bio("Part-time bartender, full-time enthusiast of the Amsterdam 'brown café' — the wood-panelled, "
                        + "candle-lit pubs where locals have been putting the world to rights for 300 years.")
                .motivation("The brown cafés are living history and the friendliest rooms in the city. I love making "
                        + "the introductions so travellers end the night with new Dutch drinking buddies, not a hangover regret.")
                .experienceInfo("Bartender since 2016; knows the owners of half the pubs in the Centrum."));

        locals.add(new LocalSpec()
                .id("emma.visser@localbuddy.demo", "Emma", "Visser", "Emma", Gender.FEMALE, "32")
                .at("amsterdam", "Amsterdam", "1071 AA", "Netherlands", "Gerard Doustraat 88, 1071 AA Amsterdam")
                .phone("+31 6 27 55 41 18")
                .languages("Dutch", "English", "French")
                .offers("hidden-gems", "cafe-hopping")
                .rating("4.95", 143)
                .bio("Art-history graduate and lifelong Amsterdammer. My obsession is the 'hofjes' — the hidden almshouse "
                        + "courtyards tucked behind ordinary front doors, silent and blooming in the middle of the city.")
                .motivation("There is a quiet, green, secret Amsterdam a few metres from the busiest streets, and almost "
                        + "no visitor finds it. Opening those little doors for people is the best part of my week.")
                .experienceInfo("MA in Art History; volunteer guide at two of the city's historic courtyards."));

        // ---- Paris ----
        locals.add(new LocalSpec()
                .id("camille.laurent@localbuddy.demo", "Camille", "Laurent", "Camille", Gender.FEMALE, "47")
                .at("paris", "Paris", "75004", "France", "12 Rue des Rosiers, 75004 Paris")
                .phone("+33 6 74 20 91 55")
                .languages("French", "English", "Spanish")
                .offers("food", "local-markets")
                .rating("4.9", 118)
                .bio("A Marais native who treats food as the mother tongue of Paris. From the falafel queue on Rue des "
                        + "Rosiers to the cheesemonger who ages his own Comté, I eat this neighbourhood for a living.")
                .motivation("Parisians are protective of their addresses — the good baker, the honest wine bar. I want to "
                        + "hand those addresses to travellers so they eat like a resident, not like a guidebook.")
                .experienceInfo("Former pastry-shop apprentice; food writer; 6 years leading small-group tastings."));

        locals.add(new LocalSpec()
                .id("hugo.moreau@localbuddy.demo", "Hugo", "Moreau", "Hugo", Gender.MALE, "13")
                .at("paris", "Paris", "75011", "France", "30 Rue de la Roquette, 75011 Paris")
                .phone("+33 6 12 88 47 63")
                .languages("French", "English")
                .offers("local-markets", "custom")
                .rating("4.7", 61)
                .bio("Eleventh-arrondissement local who plans his week around the open-air markets and the natural-wine "
                        + "cellars of eastern Paris. I cook, I forage the market, I know which stall has the best oysters.")
                .motivation("The markets are where Paris is most itself — loud, seasonal, generous. I love turning a "
                        + "market wander into a proper lunch and showing people how effortless good eating here can be.")
                .experienceInfo("Home cook and market regular; hosts market-to-table lunches and canal picnics."));

        locals.add(new LocalSpec()
                .id("lea.dubois@localbuddy.demo", "Léa", "Dubois", "Léa", Gender.FEMALE, "25")
                .at("paris", "Paris", "75018", "France", "8 Rue des Trois Frères, 75018 Paris")
                .phone("+33 6 55 30 12 84")
                .languages("French", "English", "Italian")
                .offers("photo-walk", "hidden-gems")
                .rating("4.85", 89)
                .bio("Montmartre-based illustrator and flâneuse. I spend my days on the hill's hidden staircases and in "
                        + "the 19th-century covered passages, sketching the Paris that the crowds walk straight past.")
                .motivation("Montmartre is more than the Sacré-Cœur selfie. Fifty metres away there are vineyards, "
                        + "painters' streets and empty staircases with the best view in Paris. That's what I want to show.")
                .experienceInfo("Working illustrator; leads photo and sketching walks; knows the light on every staircase."));

        locals.add(new LocalSpec()
                .id("antoine.bernard@localbuddy.demo", "Antoine", "Bernard", "Antoine", Gender.MALE, "68")
                .at("paris", "Paris", "75005", "France", "20 Rue Mouffetard, 75005 Paris")
                .phone("+33 6 33 71 26 49")
                .languages("French", "English")
                .offers("student-life", "nightlife")
                .rating("4.6", 52)
                .bio("Sorbonne graduate who never really left the Latin Quarter. I know the €4 pint, the late-night crêpe, "
                        + "and the Belleville wine bars where the city's students and artists actually spend their nights.")
                .motivation("Nightlife guides always push the expensive clubs. The real fun in Paris is cheap, loud and "
                        + "friendly — student bars and natural-wine dives. I love getting travellers into that world safely.")
                .experienceInfo("Sorbonne alum; five years running budget bar crawls; keeps groups small and looked-after."));

        return locals;
    }

    private List<ExpSpec> buildExperiences() {
        List<ExpSpec> list = new ArrayList<>();

        // ===================== AMSTERDAM =====================

        list.add(new ExpSpec()
                .host("sanne.devries@localbuddy.demo").city("amsterdam")
                .category("food", "local-markets")
                .title("Jordaan Food & Canals Walk", "ams-jordaan-food-canals-walk")
                .shortDescription("Taste your way through the Jordaan with a born-and-raised local — Dutch cheese, "
                        + "fresh herring, warm stroopwafels and apple pie, along the prettiest canals in town.")
                .description(p(
                        "Forget the fast-food stands on Damrak. This is a three-hour, six-tasting walk through the "
                                + "Jordaan — the village-within-a-city where I grew up — eating exactly where my neighbours eat.",
                        "1. We meet at the Noordermarkt and start with a warm stroopwafel pressed to order, so the "
                                + "caramel is still soft.",
                        "2. At a 100-year-old fishmonger you'll try Hollandse Nieuwe herring the Amsterdam way — with "
                                + "onion and pickle — plus a milder smoked option if raw fish is a step too far.",
                        "3. A family cheesemonger sets us up with a tasting flight of young to aged Gouda and a "
                                + "crumbly aged Reypenaer, with fig bread and mustard.",
                        "4. We duck down the Bloemgracht and Egelantiersgracht — the postcard canals with almost no "
                                + "tour boats — while I tell you how the Jordaan went from working-class to beloved.",
                        "5. A hidden bakery on a side street serves proper Dutch appeltaart with cream.",
                        "6. We finish at a 400-year-old brown café on the water with a small genever (or apple juice) "
                                + "and a plate of bitterballen. Come hungry — this replaces lunch."))
                .route("Noordermarkt square, in front of the Noorderkerk", "Café Papeneiland, Prinsengracht 2",
                        TransportMode.WALKING)
                .inclusions(bullets("6 food & drink tastings (enough to replace a meal)",
                        "Born-and-raised Jordaan guide", "Small group, max 8 guests",
                        "One genever or non-alcoholic drink at the final café", "Vegetarian option on request"))
                .exclusions(bullets("Additional drinks beyond the one included",
                        "Hotel pick-up / drop-off", "Gratuities (appreciated, never expected)"))
                .reasonsToBook(p("You want to eat where Amsterdammers actually eat, not on a tourist strip.",
                        "You'd rather walk quiet side-canals than fight the crowds on the main ring.",
                        "You like a host who can answer 'so what is it really like to live here?'"))
                .safetyNotes("Let me know about allergies (gluten, fish, dairy, nuts) at booking and I'll adapt every "
                        + "stop. The route is flat and step-free apart from a few café thresholds.")
                .details(0, 180, "55.00", 8)
                .privateBuyout("320.00")
                .geo("52.376000", "4.884600")
                .photos(
                        photo("1583778176476-4a8b02a64c01", "Dutch bites: herring, cheese and stroopwafels"),
                        photo("1467003909585-2f8a72700288", "An aged-Gouda tasting flight from a family cheesemonger"),
                        photo("1512470876302-972faa2aa9a4", "The quiet Jordaan canals we walk between tastings"),
                        photo("1488459716781-31db52582fe9", "Seasonal produce at the Noordermarkt, our meeting point"))
                .slots(slot(DayOfWeek.TUESDAY, 10, 0), slot(DayOfWeek.FRIDAY, 10, 0),
                        slot(DayOfWeek.SATURDAY, 11, 0), slot(DayOfWeek.SUNDAY, 11, 0)));

        list.add(new ExpSpec()
                .host("sanne.devries@localbuddy.demo").city("amsterdam")
                .category("food", "local-markets")
                .title("Cheese & Genever Tasting in the Jordaan", "ams-cheese-genever-tasting")
                .shortDescription("A cosy two-hour sit-down: five Dutch cheeses aged from 4 weeks to 4 years, paired "
                        + "with genever and craft beer in a candle-lit tasting room.")
                .description(p(
                        "This is the indoor, rainy-afternoon counterpart to my food walk — a relaxed, guided tasting "
                                + "rather than a march.",
                        "1. We settle into a historic tasting room off the Singel. I walk you through five cheeses, "
                                + "from a mild 4-week Gouda to a caramel-sweet, crystalline 4-year-old.",
                        "2. Between cheeses we taste genever — the juniper spirit that gin descended from — starting "
                                + "with a young style and finishing with a barrel-aged 'oude' genever.",
                        "3. A local craft lager and a non-alcoholic option are on the table too, so everyone finds a pairing.",
                        "4. I explain how the cheeses are made and aged, why the Dutch drink genever the way they do, "
                                + "and where to buy the good stuff to take home. You'll leave with a shopping list."))
                .route("Reypenaer-style tasting room, Singel 182", "House genever bar, Nieuwezijds Voorburgwal",
                        TransportMode.WALKING)
                .inclusions(bullets("5-cheese guided tasting", "2 genever pours (young + aged)",
                        "1 craft beer or non-alcoholic pairing", "Bread, mustard and accompaniments",
                        "A local's shopping list for taking flavours home"))
                .exclusions(bullets("Extra pours", "Bottles to take away", "Transport", "Gratuities"))
                .reasonsToBook(p("Perfect for a rainy Amsterdam afternoon or an evening warm-up.",
                        "Seated, cosy and social — great for couples and small groups.",
                        "You'll actually learn how to taste Dutch cheese and genever, not just sample them."))
                .safetyNotes("18+ for the genever pours; under-18s can join for the cheese with soft drinks. Please "
                        + "flag dairy or gluten allergies in advance.")
                .details(18, 120, "45.00", 10)
                .geo("52.373500", "4.888900")
                .photos(
                        photo("1524594152303-9fd13543fe6e", "Wheels of Dutch cheese aged from weeks to years"),
                        photo("1467003909585-2f8a72700288", "Our five-cheese tasting board"),
                        photo("1559339352-11d035aa65de", "Barrel-aged genever, poured to pair with each cheese"))
                .slots(slot(DayOfWeek.WEDNESDAY, 16, 0), slot(DayOfWeek.THURSDAY, 16, 0),
                        slot(DayOfWeek.SATURDAY, 15, 0)));

        list.add(new ExpSpec()
                .host("daan.bakker@localbuddy.demo").city("amsterdam")
                .category("photo-walk", "hidden-gems")
                .title("Golden Hour Canals Photo Walk", "ams-golden-hour-canals-photo-walk")
                .shortDescription("A two-hour photography walk timed for the best light of the day, from a working "
                        + "photographer who knows which bridge glows and which reflection to wait for.")
                .description(p(
                        "We chase the light. Starting late afternoon, we work a route through the Amstel and the "
                                + "eastern canals as the sun drops and the city turns gold, then blue.",
                        "1. At the Blauwbrug I set the scene and get everyone's camera or phone dialled in — exposure, "
                                + "composition, how to shoot into the low sun without blowing it out.",
                        "2. We move to a run of quiet bridges where the canal houses reflect cleanly once the tour "
                                + "boats thin out, and I coach you shot by shot.",
                        "3. As the sky shifts we set up for the Magere Brug (Skinny Bridge) lighting up — the classic "
                                + "Amsterdam night frame, done properly on a steady surface.",
                        "4. Throughout I share the compositional habits that separate a snapshot from a photograph: "
                                + "leading lines, symmetry, patience, and knowing when not to press the shutter.",
                        "Suitable for phones or cameras, complete beginners to keen hobbyists. A small tripod or "
                                + "beanbag helps for the blue-hour shots but isn't required."))
                .route("Blauwbrug bridge over the Amstel", "Magere Brug (Skinny Bridge)", TransportMode.WALKING)
                .inclusions(bullets("2-hour guided photo walk with a pro photographer", "Hands-on composition & "
                        + "exposure coaching", "Golden-hour and blue-hour shooting spots", "Max 6 guests for real "
                        + "one-on-one time", "A short list of the city's best photo locations to explore after"))
                .exclusions(bullets("Cameras or tripods (bring your own phone or camera)", "Photo editing session",
                        "Transport", "Food and drinks"))
                .reasonsToBook(p("You want photos that don't look like everyone else's.",
                        "You'll learn transferable skills, not just get walked to viewpoints.",
                        "Tiny group, so you actually get coached."))
                .safetyNotes("We stop on public bridges and canalsides; mind the traffic of bikes and keep gear away "
                        + "from the water's edge. Dress warmly — we stand still as the temperature drops.")
                .details(12, 120, "40.00", 6)
                .geo("52.366700", "4.902000")
                .photos(
                        photo("1533106418989-88406c7cc8ca", "Blue-hour reflections along the canal"),
                        photo("1512470876302-972faa2aa9a4", "Canal houses catching the last warm light"),
                        photo("1576924542622-772281b13aa8", "Bridges and bikes, the Amsterdam frame"),
                        photo("1502920917128-1aa500764cbd", "Working the streets between shooting spots"))
                .slots(slot(DayOfWeek.MONDAY, 18, 30), slot(DayOfWeek.WEDNESDAY, 18, 30),
                        slot(DayOfWeek.FRIDAY, 18, 30), slot(DayOfWeek.SATURDAY, 18, 0)));

        list.add(new ExpSpec()
                .host("pieter.janssen@localbuddy.demo").city("amsterdam")
                .category("nightlife", "cafe-hopping")
                .title("Brown Café Beer Crawl", "ams-brown-cafe-beer-crawl")
                .shortDescription("Three and a half hours through Amsterdam's wood-panelled 'brown cafés' with a "
                        + "part-time bartender who knows the owners — Dutch beers, genever and proper local welcome.")
                .description(p(
                        "A 'bruin café' is Amsterdam's living room: low ceilings stained brown by centuries of smoke, "
                                + "sand on the floor, and regulars who'll happily argue football with you. We visit four.",
                        "1. We start at one of the oldest pubs on the Spui with a Dutch pilsner poured the local way "
                                + "(yes, with a foam head — I'll explain why) and the ground rules of café etiquette.",
                        "2. Café two is all about genever: we do a small tasting of young and old styles, plus the "
                                + "ritual 'kopstootje' — a genever with a beer chaser.",
                        "3. At a canal-side café we switch to Dutch craft: a hoppy IPA and a rich bokbier, with a "
                                + "shared plate of bitterballen to keep everyone upright.",
                        "4. We finish deep in the Jordaan at a tiny café where singing sometimes breaks out and the "
                                + "owner knows me by name. This is where you meet actual Amsterdammers.",
                        "I keep the group small and the pace friendly — this is about atmosphere and conversation, "
                                + "not getting wrecked."))
                .route("Café Hoppe, Spui 18-20", "Café Chris, Bloemstraat 42", TransportMode.WALKING)
                .inclusions(bullets("4 historic brown cafés", "3 drinks included (beer / genever, non-alcoholic "
                        + "options available)", "A genever 'kopstootje' tasting", "A shared plate of bitterballen",
                        "A local bartender host and introductions to the regulars"))
                .exclusions(bullets("Drinks beyond the 3 included", "Dinner", "Transport",
                        "Anything after the tour ends (I'll point you onward)"))
                .reasonsToBook(p("You want the friendliest, most authentic side of Amsterdam nightlife.",
                        "Brown cafés are hard to 'get' as a visitor — I make the introductions.",
                        "Small group, sociable pace, real locals."))
                .safetyNotes("Strictly 18+; bring ID. I pace the group and make sure everyone gets home safe. "
                        + "Non-alcoholic options at every stop — you don't have to drink to enjoy this.")
                .details(18, 210, "49.00", 8)
                .geo("52.369000", "4.889000")
                .photos(
                        photo("1555396273-367ea4eb4db5", "Dutch beers lined up at a brown café"),
                        photo("1514933651103-005eec06c04b", "The wood-panelled, candle-lit local welcome"),
                        photo("1513622470522-26c3c8a854bc", "A genever pour between cafés"))
                .slots(slot(DayOfWeek.THURSDAY, 19, 30), slot(DayOfWeek.FRIDAY, 19, 30),
                        slot(DayOfWeek.SATURDAY, 19, 30)));

        list.add(new ExpSpec()
                .host("emma.visser@localbuddy.demo").city("amsterdam")
                .category("hidden-gems", "photo-walk")
                .title("Secret Hofjes: Amsterdam's Hidden Courtyards", "ams-secret-hofjes-courtyards")
                .shortDescription("A quiet 2.5-hour walk into the almshouse courtyards hidden behind the city's front "
                        + "doors — green, silent, centuries old, and unknown to almost every visitor.")
                .description(p(
                        "Behind ordinary doors on busy streets, Amsterdam hides dozens of 'hofjes' — almshouse "
                                + "courtyards built from the 1600s to house elderly women, still lived in, still blooming.",
                        "1. We begin at the Begijnhof, the most famous courtyard, early enough to have the hush to "
                                + "ourselves, and I tell you the story of the women who lived here for 700 years.",
                        "2. We thread through the Nine Streets to two lesser-known hofjes most tours never enter, "
                                + "where I explain the etiquette (these are people's homes — we're quiet, respectful guests).",
                        "3. Crossing into the Jordaan we visit the Karthuizerhof and one tiny, flower-filled courtyard "
                                + "I'll keep as a surprise.",
                        "4. Along the way it's the social history of the city — charity, the golden age, how these "
                                + "green pockets survived — told by an art historian who genuinely loves them.",
                        "A gentle, reflective walk, wonderful for anyone who has 'done' the big sights and wants the "
                                + "Amsterdam that stays with you."))
                .route("Begijnhof gate, off the Spui", "Karthuizerhof, Karthuizersstraat", TransportMode.WALKING)
                .inclusions(bullets("2.5-hour walking tour", "Entry to several hidden courtyards", "Art-historian "
                        + "guide", "Small group, max 8", "The stories and etiquette of the hofjes"))
                .exclusions(bullets("Food and drinks", "Museum entries", "Transport", "Gratuities"))
                .reasonsToBook(p("A calm, soulful counterpoint to the crowded centre.",
                        "You'll see places you could never find — or would walk straight past — on your own.",
                        "Beautiful, gentle and genuinely off the tourist map."))
                .safetyNotes("These are private residences: we keep voices low, don't photograph windows, and follow "
                        + "each courtyard's posted hours. Flat, easy walking throughout.")
                .details(0, 150, "38.00", 8)
                .geo("52.368800", "4.889900")
                .photos(
                        photo("1519677100203-a0e668c92439", "A hidden lane leading to a courtyard door"),
                        photo("1558551649-e44c8f992010", "Quiet corners a few metres from the crowds"),
                        photo("1534351590666-13e3e96b5017", "Bikes and blossom in the old centre"))
                .slots(slot(DayOfWeek.TUESDAY, 13, 0), slot(DayOfWeek.THURSDAY, 13, 0),
                        slot(DayOfWeek.SUNDAY, 10, 30)));

        list.add(new ExpSpec()
                .host("emma.visser@localbuddy.demo").city("amsterdam")
                .category("cafe-hopping", "food")
                .title("De Pijp Café Hopping & Coffee", "ams-de-pijp-cafe-hopping")
                .shortDescription("A relaxed 2.5-hour crawl through the cafés of De Pijp — specialty coffee, cosy "
                        + "corners and the Albert Cuyp market — with a local who works from these tables.")
                .description(p(
                        "De Pijp is Amsterdam's most café-dense, most loved neighbourhood, and I basically live in its "
                                + "coffee bars. This is a slow, sociable morning of good coffee and better conversation.",
                        "1. We meet at the Albert Cuypmarkt and grab a fresh stroopwafel and a first specialty coffee "
                                + "from a roaster I love, standing among the market stalls.",
                        "2. Café two is a design-led spot for a properly pulled flat white, where I explain how "
                                + "Amsterdam's coffee scene grew up.",
                        "3. We take our third stop in a cosy, plant-filled 'living-room' café for tea, cake, or a "
                                + "second coffee, and a proper sit-down chat.",
                        "4. We wander the side streets between stops so you get a feel for how the neighbourhood lives, "
                                + "ending by the leafy Sarphatipark.",
                        "Great for coffee lovers, digital nomads scouting work spots, and anyone who prefers their "
                                + "sightseeing at café pace."))
                .route("Albert Cuypmarkt entrance, Ferdinand Bolstraat", "Café by the Sarphatipark",
                        TransportMode.WALKING)
                .inclusions(bullets("3 café stops", "2 specialty coffees (or tea) included", "A fresh stroopwafel "
                        + "from the market", "One cake or sweet stop", "A local's map of the best cafés & work spots"))
                .exclusions(bullets("Additional drinks", "Lunch", "Transport", "Gratuities"))
                .reasonsToBook(p("You take your coffee seriously and want the good roasters, not the chains.",
                        "A gentle, social way to feel a real neighbourhood.",
                        "Perfect for remote workers hunting the best café to set up in."))
                .safetyNotes("Please mention dairy or gluten needs — most stops have oat milk and gluten-free options. "
                        + "Easy, flat walking.")
                .details(0, 150, "42.00", 6)
                .geo("52.355500", "4.891500")
                .photos(
                        photo("1495474472287-4d71bcdd2085", "A properly pulled flat white"),
                        photo("1521017432531-fbd92d768814", "A cosy De Pijp café interior"),
                        photo("1509042239860-f550ce710b93", "Specialty roasters we visit"),
                        photo("1466978913421-dad2ebd01d17", "Coffee and a fresh stroopwafel to start"))
                .slots(slot(DayOfWeek.MONDAY, 10, 0), slot(DayOfWeek.WEDNESDAY, 10, 0),
                        slot(DayOfWeek.SATURDAY, 10, 0)));

        // ===================== PARIS =====================

        list.add(new ExpSpec()
                .host("camille.laurent@localbuddy.demo").city("paris")
                .category("food", "local-markets")
                .title("Le Marais Food Crawl: Falafel to Fromage", "paris-marais-food-crawl")
                .shortDescription("Three hours of eating through the Marais with a neighbourhood native — famous "
                        + "falafel, artisan cheese and charcuterie, fresh pastry and a covered market lunch.")
                .description(p(
                        "The Marais is where old Jewish Paris, aristocratic Paris and modern Paris share one plate. "
                                + "We eat across all of it in six stops.",
                        "1. We start on Rue des Rosiers with the falafel everyone queues for — I know when to go so we "
                                + "don't — piled with aubergine, hummus and pickles.",
                        "2. At a cheesemonger who ages his own, we taste a runny Brie de Meaux, a nutty aged Comté and "
                                + "a punchy blue, with baguette and a glass of wine.",
                        "3. A charcuterie stop for rosette, jambon and rillettes, so you learn the difference by tasting it.",
                        "4. We slow down for a pastry master class: a proper croissant, then a seasonal tart or an "
                                + "éclair, with an espresso.",
                        "5. Into the Marché des Enfants Rouges — the oldest covered market in Paris — for something hot "
                                + "and savoury and a wander among the stalls.",
                        "6. We finish with a small sweet and a coffee while I write you a list of my own addresses. "
                                + "Skip breakfast; you will not need lunch."))
                .route("Rue des Rosiers, in front of L'As du Fallafel", "Marché des Enfants Rouges, Rue de Bretagne",
                        TransportMode.WALKING)
                .inclusions(bullets("6 food & drink tastings (a full meal's worth)", "A glass of wine with the cheese",
                        "An espresso with the pastries", "Marais-native guide", "Small group, max 8",
                        "Vegetarian route on request"))
                .exclusions(bullets("Extra wine or drinks", "Hotel transfers", "Gratuities"))
                .reasonsToBook(p("You want to eat like a Parisian, from addresses locals guard.",
                        "The Marais is a maze — I get you to the good stuff and past the queues.",
                        "Six real tastings, not three nibbles."))
                .safetyNotes("Tell me about allergies (gluten, dairy, nuts, pork) when you book and I'll tailor every "
                        + "stop. Lots of standing and walking on cobbles — comfortable shoes.")
                .details(0, 180, "65.00", 8)
                .privateBuyout("380.00")
                .geo("48.857500", "2.359200")
                .photos(
                        photo("1414235077428-338989a2e8c0", "A Parisian table set for tasting"),
                        photo("1543007630-9710e4a00a20", "The pastry stop: croissants and seasonal tarts"),
                        photo("1551218808-94e220e084d2", "Cheese and charcuterie boards"),
                        photo("1504674900247-0877df9cc836", "Something hot from the covered market"))
                .slots(slot(DayOfWeek.TUESDAY, 11, 0), slot(DayOfWeek.THURSDAY, 11, 0),
                        slot(DayOfWeek.SATURDAY, 11, 0), slot(DayOfWeek.SUNDAY, 11, 30)));

        list.add(new ExpSpec()
                .host("hugo.moreau@localbuddy.demo").city("paris")
                .category("local-markets", "food")
                .title("Bastille Market & Bistro Lunch", "paris-bastille-market-bistro-lunch")
                .shortDescription("Shop the huge Bastille open-air market with a local cook, taste as you go, then sit "
                        + "down to a relaxed bistro lunch built around what's in season.")
                .description(p(
                        "The Marché Bastille is one of the biggest and best street markets in Paris, and Thursday and "
                                + "Sunday mornings are when eastern Paris does its real food shopping. We dive in.",
                        "1. We walk the length of the market together while I introduce my favourite stallholders — the "
                                + "oyster man, the cheese women, the Provençal olive stand — tasting as we go.",
                        "2. You'll learn to shop like a Parisian: how to pick, what's in season, how to talk to the "
                                + "vendors, and why the queue at one stall means everything.",
                        "3. We taste oysters with a splash of lemon, seasonal fruit, a few cheeses and a cup of "
                                + "something warm, right there among the stalls.",
                        "4. Then we sit down at a nearby bistro for a relaxed two-course lunch with a glass of wine — "
                                + "simple, seasonal cooking that echoes what we just saw at the market.",
                        "You leave knowing how Parisians actually eat: at the market first, at the table second."))
                .route("Marché Bastille, Boulevard Richard-Lenoir", "A bistro near Rue de la Roquette",
                        TransportMode.WALKING)
                .inclusions(bullets("Guided market walk with tastings", "Oysters, fruit and cheese samples",
                        "A sit-down 2-course bistro lunch", "A glass of wine with lunch", "Small group, max 6"))
                .exclusions(bullets("Groceries you choose to buy", "Extra drinks", "Transport", "Gratuities"))
                .reasonsToBook(p("Markets are the soul of Paris — this is the tastiest way in.",
                        "You get both the market AND a proper lunch, not just a walkthrough.",
                        "A working cook's eye on seasonality and quality."))
                .safetyNotes("Runs on market days only (Thursday & Sunday mornings). Let me know about shellfish or "
                        + "other allergies and I'll adjust the tastings and lunch.")
                .details(0, 180, "70.00", 6)
                .geo("48.855500", "2.369000")
                .photos(
                        photo("1549144511-f099e773c147", "Flowers and produce at the Marché Bastille"),
                        photo("1519996529931-28324d5a630e", "Tasting our way along the stalls"),
                        photo("1488459716781-31db52582fe9", "Seasonal produce, picked like a local"),
                        photo("1414235077428-338989a2e8c0", "A relaxed bistro lunch to finish"))
                .slots(slot(DayOfWeek.THURSDAY, 10, 0), slot(DayOfWeek.SUNDAY, 10, 0)));

        list.add(new ExpSpec()
                .host("hugo.moreau@localbuddy.demo").city("paris")
                .category("custom", "food")
                .title("Canal Saint-Martin Picnic & Natural Wine", "paris-canal-saint-martin-picnic-wine")
                .shortDescription("A golden-evening canalside picnic with a local: natural wine from a neighbourhood "
                        + "cave, market cheese and charcuterie, and the easy Parisian art of doing nothing well.")
                .description(p(
                        "On warm evenings, all of young Paris decamps to the banks of the Canal Saint-Martin with a "
                                + "bottle and a blanket. This is that ritual, hosted — a relaxed picnic, not a march.",
                        "1. We meet at a natural-wine 'cave' where I introduce you to the movement — low-intervention, "
                                + "often local — and we choose a couple of bottles together with the owner's help.",
                        "2. We stop at a fromagerie and a charcutier for a proper spread: cheeses, saucisson, "
                                + "cornichons, a fresh baguette, olives and seasonal fruit.",
                        "3. We set up on the best stretch of the canal bank as the light goes gold, pour the wine, and "
                                + "settle in. I'll teach you how to taste natural wine — and how to spot a good bottle.",
                        "4. Mostly, we talk, snack and watch Paris drift by on the water. I'll share the eastern-Paris "
                                + "addresses I actually use — bars, bakeries, the next canal to discover.",
                        "The most quietly Parisian evening you can have. Bring a light layer for after sunset."))
                .route("Natural-wine cave near Rue de la Grange aux Belles", "Canal bank, Quai de Valmy",
                        TransportMode.WALKING)
                .inclusions(bullets("2 bottles of natural wine shared among the group", "Cheese, charcuterie, bread "
                        + "& fruit picnic", "A guided intro to natural wine", "Picnic blanket and glasses",
                        "A local's eastern-Paris address list"))
                .exclusions(bullets("Additional bottles", "Transport", "Bad-weather guarantee (we move to a wine bar "
                        + "if it rains)", "Gratuities"))
                .reasonsToBook(p("The most relaxed, most local evening in Paris.",
                        "You'll finally understand what 'natural wine' means — by drinking the good stuff.",
                        "Equal parts tasting, picnic and great conversation."))
                .safetyNotes("18+ (wine tasting). If it rains we move to a cosy natural-wine bar instead — the picnic "
                        + "just comes indoors. Please flag dairy or gluten needs.")
                .details(18, 150, "48.00", 8)
                .geo("48.872000", "2.366000")
                .photos(
                        photo("1559339352-11d035aa65de", "Choosing natural wine at the neighbourhood cave"),
                        photo("1513622470522-26c3c8a854bc", "The evening's bottles, poured canalside"),
                        photo("1520939817895-060bdaf4fe1b", "Golden hour on the Canal Saint-Martin"))
                .slots(slot(DayOfWeek.FRIDAY, 18, 0), slot(DayOfWeek.SATURDAY, 17, 0),
                        slot(DayOfWeek.SUNDAY, 16, 0)));

        list.add(new ExpSpec()
                .host("lea.dubois@localbuddy.demo").city("paris")
                .category("photo-walk", "hidden-gems")
                .title("Montmartre Hidden Staircases Photo Walk", "paris-montmartre-staircases-photo-walk")
                .shortDescription("Skip the selfie crowd. A 2.5-hour photo walk up Montmartre's secret staircases, "
                        + "painters' streets and the hidden vineyard, led by a local illustrator.")
                .description(p(
                        "Everyone climbs Montmartre the same crowded way to take the same photo. I take you up the back "
                                + "staircases the painters used, where the light is soft and the streets are empty.",
                        "1. We meet at the Art Nouveau Abbesses metro entrance and I get your camera or phone set for "
                                + "the flat morning light, then climb our first hidden staircase.",
                        "2. We wind past the pink La Maison Rose, along Rue de l'Abreuvoir and the streets Utrillo "
                                + "painted, stopping for frames the crowds never find.",
                        "3. We reach the Clos Montmartre — a real, working vineyard in the middle of Paris — and the "
                                + "quiet windmill streets above it.",
                        "4. I share how to compose the hill: framing staircases, catching a passer-by for scale, "
                                + "shooting the rooftops without the mess of tourists.",
                        "5. We finish at a viewpoint beside the Sacré-Cœur — from the side, away from the steps — for "
                                + "the whole-of-Paris shot done right.",
                        "Phones or cameras, all levels welcome. Comfortable shoes: Montmartre is a hill and we climb it."))
                .route("Abbesses metro (Art Nouveau entrance)", "Viewpoint beside the Sacré-Cœur",
                        TransportMode.WALKING)
                .inclusions(bullets("2.5-hour guided photo walk", "Composition & light coaching for phone or camera",
                        "Montmartre's hidden staircases, vineyard and painters' streets", "Max 6 guests",
                        "A map of the best light and viewpoints on the hill"))
                .exclusions(bullets("Cameras (bring your own phone or camera)", "Funicular tickets", "Food & drink",
                        "Transport"))
                .reasonsToBook(p("The Montmartre the crowds never see, at the best light of day.",
                        "Real photography coaching from a working visual artist.",
                        "Small group, quiet streets, better photos."))
                .safetyNotes("Lots of stairs and slopes — a reasonable level of mobility is needed. Watch footing on "
                        + "worn steps; we shoot from safe, public spots only.")
                .details(10, 150, "45.00", 6)
                .geo("48.884500", "2.338000")
                .photos(
                        photo("1550340499-a6c60fc8287c", "Montmartre's quiet, painterly streets"),
                        photo("1499856871958-5b9627545d1a", "Paris rooftops from a hidden viewpoint"),
                        photo("1502602898657-3e91760cbb34", "Framing the city away from the crowds"),
                        photo("1533106418989-88406c7cc8ca", "Composition coaching on the staircases"))
                .slots(slot(DayOfWeek.MONDAY, 9, 0), slot(DayOfWeek.WEDNESDAY, 9, 0),
                        slot(DayOfWeek.FRIDAY, 16, 30), slot(DayOfWeek.SATURDAY, 9, 0)));

        list.add(new ExpSpec()
                .host("lea.dubois@localbuddy.demo").city("paris")
                .category("hidden-gems", "photo-walk")
                .title("Covered Passages: Secret Paris Walk", "paris-covered-passages-secret-walk")
                .shortDescription("A two-hour walk through the 19th-century glass-roofed passages of Paris — the "
                        + "city's original shopping arcades, full of mosaics, old bookshops and forgotten charm.")
                .description(p(
                        "Before department stores there were the 'passages couverts' — glass-roofed arcades where "
                                + "Parisians strolled, shopped and gossiped out of the rain. Dozens survive, mostly unnoticed.",
                        "1. We begin in the Galerie Vivienne, the most beautiful of them all, and I explain the world "
                                + "these passages were built for — and why most disappeared.",
                        "2. We move through a chain of connected arcades — mosaicked floors, antiquarian bookshops, a "
                                + "toy soldier maker, faded painted signs — that let us cross Paris almost entirely under glass.",
                        "3. In the Passage des Panoramas, the oldest of all, we walk among old stamp dealers and tiny "
                                + "bistros, and I point out the details everyone hurries past.",
                        "4. Along the way it's the social history of 19th-century Paris — flânerie, gaslight, the birth "
                                + "of shopping — told by a local who sketches these arcades for a living.",
                        "A perfect rainy-day walk, and a side of Paris even many Parisians have never properly explored."))
                .route("Galerie Vivienne, entrance on Rue des Petits-Champs", "Passage des Panoramas, Boulevard "
                        + "Montmartre", TransportMode.WALKING)
                .inclusions(bullets("2-hour guided walk through 5+ covered passages", "The history, mosaics and "
                        + "hidden shops", "A local illustrator's eye for detail", "Small group, max 8",
                        "A self-guided map of the remaining passages"))
                .exclusions(bullets("Food and drinks", "Shopping", "Transport", "Gratuities"))
                .reasonsToBook(p("A gorgeous, weatherproof walk through a secret layer of Paris.",
                        "You'll see craftsmanship and detail the crowds walk straight past.",
                        "Great for history lovers, photographers and rainy afternoons."))
                .safetyNotes("Flat and mostly indoors — one of my most accessible walks. Some passages have posted "
                        + "closing hours; the route adapts if one is shut.")
                .details(0, 120, "36.00", 8)
                .geo("48.866700", "2.340000")
                .photos(
                        photo("1519677100203-a0e668c92439", "Inside a glass-roofed Parisian passage"),
                        photo("1431274172761-fca41d930114", "The city the arcades hide within"),
                        photo("1520939817895-060bdaf4fe1b", "Café corners along the passages"))
                .slots(slot(DayOfWeek.TUESDAY, 14, 0), slot(DayOfWeek.THURSDAY, 14, 0),
                        slot(DayOfWeek.SUNDAY, 14, 0)));

        list.add(new ExpSpec()
                .host("antoine.bernard@localbuddy.demo").city("paris")
                .category("student-life", "nightlife")
                .title("Latin Quarter Student Bar Crawl", "paris-latin-quarter-student-bar-crawl")
                .shortDescription("A 3.5-hour crawl through the Sorbonne's cheapest, liveliest student bars with a "
                        + "local grad — €4 pints, hidden cellars, a late-night crêpe and a properly fun crowd.")
                .description(p(
                        "The Latin Quarter has been student Paris since the 1200s, and its bars are still where the "
                                + "cheap drinks and the best crowd are. I graduated here and never left. We do it right.",
                        "1. We meet at the fountain on Place de la Sorbonne and start with an apéro at a classic "
                                + "student café-bar, where I lay out the plan and the ground rules.",
                        "2. Down into a vaulted medieval cellar bar for the first proper round — these stone caves have "
                                + "been drinking dens for centuries and are pure atmosphere.",
                        "3. A stop at a rowdy, sticky-floored institution famous for its cheap pints and student "
                                + "singalongs, where the group usually makes friends fast.",
                        "4. We refuel with a late-night crêpe or a jambon-beurre, because pacing matters.",
                        "5. We finish on lively Rue Mouffetard, where the bars spill onto the street and the night can "
                                + "go as long as you like.",
                        "Small group, sociable, and I keep an eye on everyone. This is about fun and meeting people, "
                                + "not a race to the bottom of a glass."))
                .route("Place de la Sorbonne fountain", "Rue Mouffetard", TransportMode.WALKING)
                .inclusions(bullets("4 student bars incl. a medieval cellar", "2 drinks included (beer, wine or soft "
                        + "drink)", "A late-night crêpe or sandwich", "A local Sorbonne-grad host", "Small, looked-after "
                        + "group"))
                .exclusions(bullets("Drinks beyond the 2 included", "Club entry after the tour", "Transport",
                        "Gratuities"))
                .reasonsToBook(p("The cheapest, friendliest, most genuine night out in Paris.",
                        "Solo travellers love this — you'll leave with a group.",
                        "A local who actually knows the bartenders and keeps the night safe."))
                .safetyNotes("Strictly 18+; bring ID. I keep the group together, watch the pace, and make sure "
                        + "everyone knows their way home. Non-alcoholic options at every stop.")
                .details(18, 210, "39.00", 10)
                .geo("48.848900", "2.343000")
                .photos(
                        photo("1523240795612-9a054b0db644", "A lively student crowd in the Latin Quarter"),
                        photo("1541167760496-1628856ab772", "Making friends between bars"),
                        photo("1514933651103-005eec06c04b", "A vaulted cellar bar, centuries old"),
                        photo("1470229722913-7c0e2dbbafd3", "The night gets going on Rue Mouffetard"))
                .slots(slot(DayOfWeek.WEDNESDAY, 20, 0), slot(DayOfWeek.THURSDAY, 20, 0),
                        slot(DayOfWeek.FRIDAY, 20, 30), slot(DayOfWeek.SATURDAY, 20, 30)));

        list.add(new ExpSpec()
                .host("antoine.bernard@localbuddy.demo").city("paris")
                .category("nightlife", "food")
                .title("Belleville Natural Wine Bars After Dark", "paris-belleville-natural-wine-bars")
                .shortDescription("A three-hour crawl through the natural-wine bars of Belleville and Ménilmontant — "
                        + "the artists' east of Paris — with small plates, street art and a local in the know.")
                .description(p(
                        "Belleville is the Paris that Parisians move to: hilly, multicultural, covered in street art, "
                                + "and home to the city's best natural-wine bars. We spend an evening among them.",
                        "1. We meet at the Belleville metro and I set the scene — the neighbourhood's immigrant history, "
                                + "its artists, and why the natural-wine movement took root here.",
                        "2. Bar one is a tiny 'cave à manger' for our first glasses and a small plate — I teach you to "
                                + "read a natural-wine list and order with confidence.",
                        "3. We walk up through the street-art alleys of Rue Dénoyez to a second bar with a different "
                                + "style, pairing wine with charcuterie or cheese.",
                        "4. A final, buzzier spot where locals pack in late — one more glass, one more plate, and the "
                                + "best of the evening's conversation.",
                        "Throughout, I share the addresses I actually drink at, so you can come back on your own. "
                                + "Relaxed, curious and delicious rather than rowdy."))
                .route("Belleville metro exit, Boulevard de Belleville", "Rue Dénoyez", TransportMode.WALKING)
                .inclusions(bullets("3 natural-wine bars", "3 glasses of natural wine included", "Small plates / "
                        + "charcuterie along the way", "A guided intro to natural wine", "Street-art walk between bars",
                        "Small group, max 6"))
                .exclusions(bullets("Extra glasses and bottles", "Full dinner", "Transport", "Gratuities"))
                .reasonsToBook(p("The coolest, least touristy nightlife in Paris.",
                        "You'll learn to love and order natural wine.",
                        "Street art, small plates and a local who knows every pour."))
                .safetyNotes("18+ (wine tasting); bring ID. Belleville is hilly — comfortable shoes. I keep the group "
                        + "together and everyone oriented for the trip home.")
                .details(18, 180, "58.00", 6)
                .privateBuyout("300.00")
                .geo("48.872000", "2.377000")
                .photos(
                        photo("1470337458703-46ad1756a187", "A natural-wine pour in a Belleville bar"),
                        photo("1559339352-11d035aa65de", "Reading the list at a cave à manger"),
                        photo("1516450360452-9312f5e86fc7", "Belleville after dark"),
                        photo("1513622470522-26c3c8a854bc", "Small plates and a final glass"))
                .slots(slot(DayOfWeek.THURSDAY, 19, 0), slot(DayOfWeek.FRIDAY, 19, 0),
                        slot(DayOfWeek.SATURDAY, 19, 0)));

        return list;
    }
}
