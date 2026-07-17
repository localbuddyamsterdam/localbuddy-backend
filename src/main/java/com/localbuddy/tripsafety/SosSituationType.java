package com.localbuddy.tripsafety;

/**
 * The kind of help a traveler needs, chosen with a single tap after raising an SOS.
 * Defaults to {@link #EMERGENCY} so an SOS with no category is still treated as
 * life-threatening — the panic UI stays a single button.
 */
public enum SosSituationType {
    EMERGENCY,
    FEEL_UNSAFE,
    MEDICAL,
    INJURED,
    LOST,
    OTHER
}
