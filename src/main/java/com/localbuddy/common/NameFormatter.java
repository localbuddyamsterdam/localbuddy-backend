package com.localbuddy.common;

import com.localbuddy.common.exception.BadRequestException;

/**
 * Person-name normalization shared across accounts, host profiles and guest bookings.
 *
 * <p>{@link #titleCase(String)} capitalizes the first letter of every word — split on
 * whitespace, hyphens, apostrophes and dots — and lowercases the rest, so
 * {@code "jOHN o'brien-smith"} becomes {@code "John O'Brien-Smith"}. Internal runs of
 * whitespace collapse to a single space.
 *
 * <p>{@link #requiredName(String, String, int)} additionally trims, enforces a minimum
 * length on the trimmed value and throws {@link BadRequestException} on blank / too-short
 * input; {@link #optionalName(String)} returns {@code null} for blank input.
 *
 * <p>The canonical rules (see callers): first name required (min 1), last name required
 * (min 2 — a single character is rejected), preferred name optional.
 */
public final class NameFormatter {

    /** Minimum length for a first name (any non-blank value). */
    public static final int FIRST_NAME_MIN = 1;

    /** Minimum length for a last name — a single character is not accepted. */
    public static final int LAST_NAME_MIN = 2;

    private NameFormatter() {
    }

    /**
     * Capitalizes the first letter of each word and lowercases the remaining letters.
     * Word boundaries are whitespace, {@code -}, {@code '} / {@code ’} and {@code .}.
     * Runs of whitespace collapse to a single space. Null-safe: returns {@code null}
     * for {@code null} input, {@code ""} for blank input.
     */
    public static String titleCase(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        StringBuilder sb = new StringBuilder(trimmed.length());
        boolean startOfWord = true;
        boolean prevWhitespace = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevWhitespace) {
                    sb.append(' ');
                }
                startOfWord = true;
                prevWhitespace = true;
            } else if (c == '-' || c == '\'' || c == '’' || c == '.') {
                sb.append(c);
                startOfWord = true;
                prevWhitespace = false;
            } else {
                sb.append(startOfWord ? Character.toTitleCase(c) : Character.toLowerCase(c));
                startOfWord = false;
                prevWhitespace = false;
            }
        }
        return sb.toString();
    }

    /**
     * Trims, enforces {@code minLength} on the trimmed value and title-cases a required
     * name field. Throws {@link BadRequestException} keyed to {@code label} on blank or
     * too-short input.
     */
    public static String requiredName(String raw, String label, int minLength) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException(label + " is required");
        }
        if (trimmed.length() < minLength) {
            throw new BadRequestException(label + " must be at least " + minLength + " characters");
        }
        return titleCase(trimmed);
    }

    /**
     * Splits a combined full name into {@code {first, last}}, title-cased. Splits on the first
     * whitespace; a single-token value seeds both entries so a required last name is always
     * populated. Blank input yields {@code {"", ""}}. Used to migrate legacy combined names
     * (dev-admin seed, social-login display names) into the split model.
     */
    public static String[] splitFullName(String raw) {
        String titled = titleCase(raw);
        if (titled == null || titled.isEmpty()) {
            return new String[]{"", ""};
        }
        int sp = titled.indexOf(' ');
        if (sp > 0 && sp < titled.length() - 1) {
            return new String[]{titled.substring(0, sp).trim(), titled.substring(sp + 1).trim()};
        }
        return new String[]{titled, titled};
    }

    /** Title-cases an optional name; returns {@code null} for {@code null}/blank input. */
    public static String optionalName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : titleCase(trimmed);
    }
}
