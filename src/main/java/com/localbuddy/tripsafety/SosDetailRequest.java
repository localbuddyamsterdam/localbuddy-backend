package com.localbuddy.tripsafety;

import jakarta.validation.constraints.Size;

/**
 * Follow-up detail a traveler adds to an already-raised SOS via the one-tap chips on
 * the reassurance screen (situation type, contactability, an optional note). Every
 * field is optional — this only ever enriches an existing event, never gates it.
 */
public record SosDetailRequest(
        SosSituationType situationType,
        SosContactPreference contactPreference,
        @Size(max = 2000) String message
) {
}
