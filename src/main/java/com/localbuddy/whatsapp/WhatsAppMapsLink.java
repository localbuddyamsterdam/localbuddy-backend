package com.localbuddy.whatsapp;

import com.localbuddy.experience.Experience;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the dynamic URL suffix for an "Open in Maps" WhatsApp button — the part appended to a
 * Google Maps search button whose static base ({@code …/maps/search/?api=1&query=}) is configured
 * on the template itself in Meta. Falls back from precise coordinates to meeting area + city to
 * just the city, so the button always has a value (a template with a URL button requires one).
 */
public final class WhatsAppMapsLink {

    private WhatsAppMapsLink() {
    }

    public static String querySuffix(Experience experience) {
        if (experience == null) {
            return "";
        }
        if (experience.getLatitude() != null && experience.getLongitude() != null) {
            return experience.getLatitude().toPlainString() + "%2C" + experience.getLongitude().toPlainString();
        }
        String cityName = experience.getCity() != null ? experience.getCity().getName() : null;
        String meetingArea = experience.getMeetingArea();
        String query = meetingArea != null && !meetingArea.isBlank()
                ? (cityName != null && !cityName.isBlank() ? meetingArea + ", " + cityName : meetingArea)
                : cityName;
        return query == null || query.isBlank() ? "" : URLEncoder.encode(query, StandardCharsets.UTF_8);
    }
}
