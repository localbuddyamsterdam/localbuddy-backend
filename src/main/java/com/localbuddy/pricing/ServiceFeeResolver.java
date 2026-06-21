package com.localbuddy.pricing;

import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Resolves the customer service-fee rate. Same scope precedence as commission,
 * though in practice it is usually a single PLATFORM rate (admin-configurable).
 */
@Service
public class ServiceFeeResolver {

    private final ServiceFeeRuleRepository serviceFeeRuleRepository;
    private final BigDecimal configDefaultRate;

    public ServiceFeeResolver(ServiceFeeRuleRepository serviceFeeRuleRepository,
                              @Value("${app.fees.service-fee-percentage:2.5}") BigDecimal serviceFeePercentage) {
        this.serviceFeeRuleRepository = serviceFeeRuleRepository;
        this.configDefaultRate = serviceFeePercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal resolve(Experience experience, LocalProfile host, Instant now) {
        BigDecimal rate = firstRate(serviceFeeRuleRepository.findActive(ScopeType.EXPERIENCE, experience.getId(), now));
        if (rate != null) {
            return rate;
        }
        if (host != null) {
            rate = firstRate(serviceFeeRuleRepository.findActive(ScopeType.HOST, host.getId(), now));
            if (rate != null) {
                return rate;
            }
        }
        if (experience.getCategory() != null) {
            rate = firstRate(serviceFeeRuleRepository.findActive(ScopeType.CATEGORY, experience.getCategory().getId(), now));
            if (rate != null) {
                return rate;
            }
        }
        if (experience.getCity() != null) {
            rate = firstRate(serviceFeeRuleRepository.findActive(ScopeType.CITY, experience.getCity().getId(), now));
            if (rate != null) {
                return rate;
            }
        }
        rate = firstRate(serviceFeeRuleRepository.findActive(ScopeType.PLATFORM, null, now));
        return rate != null ? rate : configDefaultRate;
    }

    private BigDecimal firstRate(List<ServiceFeeRule> rules) {
        return rules.isEmpty() ? null : rules.get(0).getRate();
    }
}
