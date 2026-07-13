package com.localbuddy.experience;

import java.math.BigDecimal;

/**
 * Set or clear an experience's per-experience commission override.
 * {@code commissionRate == null} clears the override. Bounds (0..max) are
 * validated in the service so the null-clear case stays expressible.
 */
public record SetExperienceCommissionRequest(BigDecimal commissionRate) {}
