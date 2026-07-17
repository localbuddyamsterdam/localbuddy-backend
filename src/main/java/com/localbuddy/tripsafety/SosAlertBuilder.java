package com.localbuddy.tripsafety;

import com.localbuddy.booking.Booking;
import com.localbuddy.user.User;
import org.springframework.stereotype.Component;

/**
 * Turns an SOS event into an alert an operator can act on in one tap, instead of a bare
 * pair of decimals. Produces a plain-text fallback and a click-to-call HTML body with:
 * Google/Apple Maps deep links to the location, and tel:/WhatsApp links for the
 * traveler, the host, and the emergency contact.
 *
 * <p>Location is best-effort: when the device could not get a fix the alert says so
 * prominently rather than printing a misleading 0,0.
 */
@Component
public class SosAlertBuilder {

    /** Rendered alert: a subject line, a plain-text body, and an HTML body. */
    public record SosAlertContent(String subject, String textBody, String htmlBody) {
    }

    public SosAlertContent build(Booking booking, TripSafetyEvent event, EmergencyContact profileEc, boolean update) {
        String ref = safe(booking.getBookingReference());
        String situation = situationLabel(event.getSituationType());
        String contact = contactLabel(event.getContactPreference());
        String city = booking.getExperience() != null && booking.getExperience().getCity() != null
                ? booking.getExperience().getCity().getName() : null;
        String expTitle = booking.getExperience() != null ? booking.getExperience().getTitle() : null;

        User traveler = booking.getLoggedInUser();
        String travelerName = traveler != null ? traveler.getDisplayName() : booking.getGuestName();
        String travelerPhone = traveler != null ? traveler.getPhone() : booking.getGuestPhone();

        String hostName = null;
        String hostPhone = null;
        if (booking.getLocalProfile() != null && booking.getLocalProfile().getUser() != null) {
            User host = booking.getLocalProfile().getUser();
            hostName = host.getDisplayName();
            hostPhone = host.getPhone();
        }

        // Emergency contact: prefer the frozen per-booking snapshot, fall back to the profile EC.
        String ecName;
        String ecPhone;
        if (notBlank(booking.getEmergencyContactPhone())) {
            ecName = join(booking.getEmergencyContactFirstName(), booking.getEmergencyContactLastName());
            ecPhone = booking.getEmergencyContactPhone();
        } else if (profileEc != null) {
            ecName = join(profileEc.getFirstName(), profileEc.getLastName());
            ecPhone = profileEc.getContactPhone();
        } else {
            ecName = null;
            ecPhone = null;
        }

        Double lat = event.getLatitude();
        Double lng = event.getLongitude();
        boolean hasLoc = lat != null && lng != null;

        String subject = (update ? "[SOS UPDATE] " : "[SOS] ") + situation
                + " · " + (city != null ? city + " · " : "") + "booking " + ref;

        String text = buildText(update, situation, contact, event, ref, expTitle,
                travelerName, travelerPhone, hostName, hostPhone, ecName, ecPhone, lat, lng, hasLoc);
        String html = buildHtml(update, situation, contact, event, ref, expTitle, city,
                travelerName, travelerPhone, hostName, hostPhone, ecName, ecPhone, lat, lng, hasLoc);

        return new SosAlertContent(subject, text, html);
    }

    // --- plain text --------------------------------------------------------

    private String buildText(boolean update, String situation, String contact, TripSafetyEvent event,
                             String ref, String expTitle, String travelerName, String travelerPhone,
                             String hostName, String hostPhone, String ecName, String ecPhone,
                             Double lat, Double lng, boolean hasLoc) {
        StringBuilder t = new StringBuilder();
        t.append(update ? "SOS UPDATED by traveler.\n\n" : "SOS RAISED.\n\n");
        t.append("Situation: ").append(situation).append('\n');
        t.append("Contact:   ").append(contact).append('\n');
        t.append("Booking:   ").append(ref);
        if (notBlank(expTitle)) {
            t.append(" — ").append(expTitle);
        }
        t.append('\n');
        if (notBlank(travelerName) || notBlank(travelerPhone)) {
            t.append("Traveler:  ").append(nz(travelerName));
            if (notBlank(travelerPhone)) {
                t.append("  ").append(travelerPhone);
            }
            t.append('\n');
        }
        if (hasLoc) {
            t.append("Location:  ").append(lat).append(", ").append(lng);
            if (event.getAccuracyMeters() != null) {
                t.append(" (±").append(Math.round(event.getAccuracyMeters())).append(" m)");
            }
            t.append('\n');
            t.append("Map:       ").append(googleMaps(lat, lng)).append('\n');
        } else {
            t.append("Location:  UNAVAILABLE — the device could not get a fix.\n");
        }
        if (notBlank(event.getGeocodedAddress())) {
            t.append("Address:   ").append(event.getGeocodedAddress()).append('\n');
        }
        if (event.getBatteryPercent() != null) {
            t.append("Battery:   ").append(event.getBatteryPercent()).append("%\n");
        }
        if (notBlank(event.getNote())) {
            t.append("Message:   ").append(event.getNote()).append('\n');
        }
        if (notBlank(hostName) || notBlank(hostPhone)) {
            t.append("Host:      ").append(nz(hostName));
            if (notBlank(hostPhone)) {
                t.append("  ").append(hostPhone);
            }
            t.append('\n');
        }
        if (notBlank(ecPhone)) {
            t.append("Emergency contact: ").append(nz(ecName)).append("  ").append(ecPhone).append('\n');
        }
        return t.toString();
    }

    // --- html --------------------------------------------------------------

    private String buildHtml(boolean update, String situation, String contact, TripSafetyEvent event,
                             String ref, String expTitle, String city, String travelerName, String travelerPhone,
                             String hostName, String hostPhone, String ecName, String ecPhone,
                             Double lat, Double lng, boolean hasLoc) {
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;max-width:560px;")
                .append("margin:0 auto;color:#16161a;line-height:1.5\">");

        // Header
        h.append("<div style=\"background:#c0241f;color:#fff;padding:14px 18px;border-radius:10px 10px 0 0;")
                .append("font-size:17px;font-weight:700\">")
                .append(update ? "SOS updated" : "SOS raised")
                .append(" · ").append(escape(situation));
        if (city != null) {
            h.append(" · ").append(escape(city));
        }
        h.append("</div>");

        h.append("<div style=\"border:1px solid #e4e2dd;border-top:0;border-radius:0 0 10px 10px;padding:18px\">");

        // Contactability banner (only surface when it constrains the responder)
        SosContactPreference pref = event.getContactPreference();
        if (pref == SosContactPreference.TEXT_ONLY || pref == SosContactPreference.DO_NOT_CONTACT) {
            h.append("<div style=\"background:#f8f0dd;border:1px solid #e6cf8f;color:#7a5a12;")
                    .append("padding:9px 12px;border-radius:8px;font-weight:600;font-size:13px;margin-bottom:14px\">⚠ ")
                    .append(escape(contact)).append("</div>");
        }

        // Key facts
        h.append("<table style=\"width:100%;border-collapse:collapse;font-size:14px\">");
        row(h, "Booking", escape(ref) + (notBlank(expTitle) ? " — " + escape(expTitle) : ""));
        if (notBlank(travelerName)) {
            row(h, "Traveler", escape(travelerName));
        }
        row(h, "Contact", escape(contact));
        if (hasLoc) {
            String acc = event.getAccuracyMeters() != null
                    ? " (±" + Math.round(event.getAccuracyMeters()) + " m)" : "";
            row(h, "Location", escape(lat + ", " + lng) + acc);
        } else {
            row(h, "Location", "<b style=\"color:#c0241f\">Unavailable — device could not get a fix</b>");
        }
        if (notBlank(event.getGeocodedAddress())) {
            row(h, "Address", escape(event.getGeocodedAddress()));
        }
        if (event.getBatteryPercent() != null) {
            String warn = event.getBatteryPercent() <= 15 ? ";color:#c0241f;font-weight:700" : "";
            h.append("<tr><td style=\"color:#74747c;padding:4px 12px 4px 0;vertical-align:top\">Battery</td>")
                    .append("<td style=\"padding:4px 0").append(warn).append("\">")
                    .append(event.getBatteryPercent()).append("%</td></tr>");
        }
        if (notBlank(event.getNote())) {
            row(h, "Message", "“" + escape(event.getNote()) + "”");
        }
        h.append("</table>");

        // Actions
        h.append("<div style=\"margin-top:16px\">");
        if (hasLoc) {
            button(h, "📍 Open in Google Maps", googleMaps(lat, lng), true);
            button(h, "Apple Maps", appleMaps(lat, lng), false);
        }
        if (notBlank(travelerPhone)) {
            button(h, "☎ Call " + shortName(travelerName, "traveler"), telHref(travelerPhone), false);
        }
        if (notBlank(hostPhone)) {
            button(h, "Call host" + (notBlank(hostName) ? " (" + escape(hostName) + ")" : ""), telHref(hostPhone), false);
        }
        if (notBlank(ecPhone)) {
            button(h, "Call contact" + (notBlank(ecName) ? " (" + escape(ecName) + ")" : ""), telHref(ecPhone), false);
            button(h, "WhatsApp contact", waHref(ecPhone), false);
        }
        h.append("</div>");

        h.append("</div></div>");
        return h.toString();
    }

    private void row(StringBuilder h, String label, String valueHtml) {
        h.append("<tr><td style=\"color:#74747c;padding:4px 12px 4px 0;vertical-align:top;white-space:nowrap\">")
                .append(escape(label)).append("</td><td style=\"padding:4px 0\">").append(valueHtml).append("</td></tr>");
    }

    private void button(StringBuilder h, String label, String href, boolean primary) {
        String bg = primary ? "#c0241f" : "#f2f1ee";
        String fg = primary ? "#ffffff" : "#16161a";
        h.append("<a href=\"").append(escapeAttr(href)).append("\" ")
                .append("style=\"display:inline-block;margin:0 8px 8px 0;padding:10px 15px;border-radius:999px;")
                .append("background:").append(bg).append(";color:").append(fg).append(";text-decoration:none;")
                .append("font-weight:600;font-size:14px\">").append(escape(label)).append("</a>");
    }

    // --- labels ------------------------------------------------------------

    private String situationLabel(SosSituationType type) {
        if (type == null) {
            return "Emergency";
        }
        return switch (type) {
            case EMERGENCY -> "EMERGENCY";
            case FEEL_UNSAFE -> "Feels unsafe";
            case MEDICAL -> "Medical";
            case INJURED -> "Injured";
            case LOST -> "Lost / stranded";
            case OTHER -> "Needs help";
        };
    }

    private String contactLabel(SosContactPreference pref) {
        if (pref == null) {
            return "OK to call";
        }
        return switch (pref) {
            case CALL -> "OK to call";
            case TEXT_ONLY -> "TEXT ONLY — do not call";
            case DO_NOT_CONTACT -> "DO NOT CONTACT — traveler will reach out";
        };
    }

    // --- helpers -----------------------------------------------------------

    private String googleMaps(Double lat, Double lng) {
        return "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng;
    }

    private String appleMaps(Double lat, Double lng) {
        return "https://maps.apple.com/?ll=" + lat + "," + lng + "&q=Traveler";
    }

    private String telHref(String phone) {
        return "tel:" + phone.replaceAll("[^+0-9]", "");
    }

    private String waHref(String phone) {
        return "https://wa.me/" + phone.replaceAll("[^0-9]", "");
    }

    private String shortName(String name, String fallback) {
        if (!notBlank(name)) {
            return fallback;
        }
        String first = name.trim().split("\\s+")[0];
        return first.isEmpty() ? fallback : first;
    }

    private String join(String a, String b) {
        String s = (nz(a) + " " + nz(b)).trim();
        return s.isEmpty() ? null : s;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String safe(String s) {
        return s == null ? "—" : s;
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String escapeAttr(String s) {
        return escape(s);
    }
}
