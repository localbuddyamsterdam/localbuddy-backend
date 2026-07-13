package com.localbuddy.localprofile;

import java.math.BigDecimal;

/**
 * Set or clear a host's per-host commission override.
 * {@code commissionRate == null} clears the override. Bounds (0..max) are
 * validated in the service so the null-clear case stays expressible.
 */
public record SetHostCommissionRequest(BigDecimal commissionRate) {}
