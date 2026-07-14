package com.localbuddy.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates strong random temporary passwords that satisfy {@link StrongPassword}
 * (8-100 chars, no whitespace, and at least one uppercase, lowercase, digit and
 * special character). Used for admin-issued temporary passwords — onboarding a
 * new user, or resetting a password on the user's behalf.
 *
 * <p>Ambiguous glyphs (0/O, 1/l/I) are excluded so the password is easy for an
 * admin to read aloud or copy without transcription errors.
 */
@Component
public class TemporaryPasswordGenerator {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"; // no I, O
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz"; // no l, o
    private static final String DIGIT = "23456789";                // no 0, 1
    private static final String SPECIAL = "!@#$%^&*?-_";
    private static final String ALL = UPPER + LOWER + DIGIT + SPECIAL;

    private static final int LENGTH = 14;

    private final SecureRandom random = new SecureRandom();

    /** A 14-char password guaranteed to include each required character class. */
    public String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        // Seed one of each required class up front so the StrongPassword rules always pass...
        chars.add(randomChar(UPPER));
        chars.add(randomChar(LOWER));
        chars.add(randomChar(DIGIT));
        chars.add(randomChar(SPECIAL));
        // ...then fill the rest from the full pool.
        for (int i = chars.size(); i < LENGTH; i++) {
            chars.add(randomChar(ALL));
        }
        // Shuffle so the guaranteed classes aren't always in the first four positions.
        Collections.shuffle(chars, random);

        StringBuilder sb = new StringBuilder(LENGTH);
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }

    private char randomChar(String pool) {
        return pool.charAt(random.nextInt(pool.length()));
    }
}
