package com.localbuddy.experience;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Best-effort resolution of an IANA timezone (e.g. {@code Europe/Amsterdam}, {@code America/New_York})
 * from a city name + country, so an admin types only the city and country and the zone is filled in
 * automatically. Strategy, in order:
 *
 * <ol>
 *   <li><b>City → zone.</b> IANA zone ids are named after representative cities, so a city that IS a
 *       zone representative (Amsterdam, Paris, New York, London, Tokyo, …) resolves exactly from the
 *       JDK's bundled tz database. When a city name maps to zones on several continents, the country
 *       disambiguates (and guards against same-named cities abroad, e.g. Paris, Texas).</li>
 *   <li><b>Country → zone.</b> For a city that is not itself a zone representative (Rotterdam, Lyon,
 *       …), fall back to the country's representative zone — exact for single-timezone countries;
 *       for multi-timezone countries it is the capital's zone (a sensible default an admin can
 *       override).</li>
 *   <li><b>Platform default.</b> {@code Europe/Amsterdam}, matching the historical fallback.</li>
 * </ol>
 *
 * Fully offline: everything derives from the JDK tz database plus a small country→zone table — no
 * geocoding, no network. New IANA cities are picked up automatically on JDK/tzdata updates.
 */
@Component
public class CityTimezoneResolver {

    public static final String DEFAULT_ZONE = "Europe/Amsterdam";

    /** normalized city segment -> zone ids whose last path element matches it. */
    private static final Map<String, Set<String>> CITY_TO_ZONES = buildCityIndex();

    /** normalized country name/ISO code -> representative IANA zone. */
    private static final Map<String, String> COUNTRY_TO_ZONE = buildCountryTable();

    /** Resolve the zone for a city + country, never null (falls back to {@link #DEFAULT_ZONE}). */
    public String resolve(String cityName, String country) {
        String byCity = resolveByCity(cityName, country);
        if (byCity != null) {
            return byCity;
        }
        String byCountry = COUNTRY_TO_ZONE.get(normalize(country));
        return byCountry != null ? byCountry : DEFAULT_ZONE;
    }

    private String resolveByCity(String cityName, String country) {
        Set<String> zones = CITY_TO_ZONES.get(normalize(cityName));
        if (zones == null || zones.isEmpty()) {
            return null;
        }
        String countryZone = COUNTRY_TO_ZONE.get(normalize(country));
        if (countryZone != null) {
            // Keep only zones on the country's continent (the first path element, e.g. "Europe/").
            // This disambiguates same-named cities and rejects a famous city's zone when the country
            // says it's actually elsewhere (Paris, Texas → not Europe/Paris).
            String continent = countryZone.substring(0, countryZone.indexOf('/') + 1);
            TreeSet<String> onContinent = new TreeSet<>();
            for (String zone : zones) {
                if (zone.startsWith(continent)) {
                    onContinent.add(zone);
                }
            }
            if (!onContinent.isEmpty()) {
                return onContinent.first();
            }
            // City name matched only zones on other continents → not the famous one; use the country.
            return countryZone;
        }
        // Unknown country: a single match wins; otherwise a deterministic pick (stable across runs).
        return new TreeSet<>(zones).first();
    }

    private static Map<String, Set<String>> buildCityIndex() {
        Map<String, Set<String>> index = new HashMap<>();
        for (String zoneId : ZoneId.getAvailableZoneIds()) {
            int slash = zoneId.lastIndexOf('/');
            if (slash < 0) {
                continue; // "UTC", "GMT", offset ids — not city zones
            }
            String segment = normalize(zoneId.substring(slash + 1));
            if (!segment.isEmpty()) {
                index.computeIfAbsent(segment, k -> new TreeSet<>()).add(zoneId);
            }
        }
        return index;
    }

    /** Accent-stripped, lower-cased, spaces/hyphens → underscores — matches IANA segment style. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String stripped = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^a-z0-9_]", "");
    }

    private static Map<String, String> buildCountryTable() {
        Map<String, String> m = new HashMap<>();
        // Europe (LocalBuddy's core markets)
        put(m, "Europe/Amsterdam", "netherlands", "the_netherlands", "holland", "nl", "nld");
        put(m, "Europe/Paris", "france", "fr", "fra");
        put(m, "Europe/Brussels", "belgium", "be", "bel");
        put(m, "Europe/Berlin", "germany", "deutschland", "de", "deu");
        put(m, "Europe/Madrid", "spain", "espana", "es", "esp");
        put(m, "Europe/Rome", "italy", "italia", "it", "ita");
        put(m, "Europe/Lisbon", "portugal", "pt", "prt");
        put(m, "Europe/Dublin", "ireland", "ie", "irl");
        put(m, "Europe/London", "united_kingdom", "uk", "gb", "gbr", "great_britain", "england", "scotland", "wales");
        put(m, "Europe/Vienna", "austria", "at", "aut");
        put(m, "Europe/Zurich", "switzerland", "ch", "che");
        put(m, "Europe/Prague", "czech_republic", "czechia", "cz", "cze");
        put(m, "Europe/Warsaw", "poland", "pl", "pol");
        put(m, "Europe/Budapest", "hungary", "hu", "hun");
        put(m, "Europe/Athens", "greece", "gr", "grc");
        put(m, "Europe/Copenhagen", "denmark", "dk", "dnk");
        put(m, "Europe/Stockholm", "sweden", "se", "swe");
        put(m, "Europe/Oslo", "norway", "no", "nor");
        put(m, "Europe/Helsinki", "finland", "fi", "fin");
        put(m, "Europe/Reykjavik", "iceland", "is", "isl");
        put(m, "Europe/Bucharest", "romania", "ro", "rou");
        put(m, "Europe/Sofia", "bulgaria", "bg", "bgr");
        put(m, "Europe/Zagreb", "croatia", "hr", "hrv");
        put(m, "Europe/Belgrade", "serbia", "rs", "srb");
        put(m, "Europe/Moscow", "russia", "ru", "rus");
        put(m, "Europe/Kyiv", "ukraine", "ua", "ukr");
        put(m, "Europe/Istanbul", "turkey", "turkiye", "tr", "tur");
        // Americas
        put(m, "America/New_York", "united_states", "united_states_of_america", "usa", "us", "america");
        put(m, "America/Toronto", "canada", "ca", "can");
        put(m, "America/Mexico_City", "mexico", "mx", "mex");
        put(m, "America/Sao_Paulo", "brazil", "brasil", "br", "bra");
        put(m, "America/Argentina/Buenos_Aires", "argentina", "ar", "arg");
        put(m, "America/Santiago", "chile", "cl", "chl");
        put(m, "America/Bogota", "colombia", "co", "col");
        put(m, "America/Lima", "peru", "pe", "per");
        // Middle East / Africa
        put(m, "Asia/Dubai", "united_arab_emirates", "uae", "ae", "are");
        put(m, "Asia/Jerusalem", "israel", "il", "isr");
        put(m, "Africa/Cairo", "egypt", "eg", "egy");
        put(m, "Africa/Casablanca", "morocco", "ma", "mar");
        put(m, "Africa/Johannesburg", "south_africa", "za", "zaf");
        put(m, "Africa/Lagos", "nigeria", "ng", "nga");
        put(m, "Africa/Nairobi", "kenya", "ke", "ken");
        // Asia / Pacific
        put(m, "Asia/Tokyo", "japan", "jp", "jpn");
        put(m, "Asia/Shanghai", "china", "cn", "chn");
        put(m, "Asia/Hong_Kong", "hong_kong", "hk", "hkg");
        put(m, "Asia/Singapore", "singapore", "sg", "sgp");
        put(m, "Asia/Seoul", "south_korea", "korea", "kr", "kor");
        put(m, "Asia/Bangkok", "thailand", "th", "tha");
        put(m, "Asia/Jakarta", "indonesia", "id", "idn");
        put(m, "Asia/Kolkata", "india", "in", "ind");
        put(m, "Asia/Manila", "philippines", "ph", "phl");
        put(m, "Asia/Kuala_Lumpur", "malaysia", "my", "mys");
        put(m, "Australia/Sydney", "australia", "au", "aus");
        put(m, "Pacific/Auckland", "new_zealand", "nz", "nzl");
        return m;
    }

    private static void put(Map<String, String> m, String zone, String... keys) {
        for (String key : keys) {
            m.put(key, zone);
        }
    }
}
