package com.localbuddy.incident;

/**
 * Outcome of a single dependency health probe.
 *
 * @param healthy   whether the dependency answered successfully
 * @param detail    short human-readable note (an "ok" summary, or the failure's exception + root message)
 * @param latencyMs how long the probe took (a slow-but-healthy probe is still a useful signal in the admin view)
 */
public record ProbeResult(boolean healthy, String detail, long latencyMs) {
}
