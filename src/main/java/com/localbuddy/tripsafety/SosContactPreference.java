package com.localbuddy.tripsafety;

/**
 * Whether — and how — it is safe to contact the traveler right now. Safety-critical:
 * a traveler hiding from someone must be able to say "do not call", so a ringing phone
 * never gives them away.
 */
public enum SosContactPreference {
    CALL,
    TEXT_ONLY,
    DO_NOT_CONTACT
}
