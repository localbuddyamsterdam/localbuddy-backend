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
 * Resolves the commission rate for a booking. Precedence (first match wins):
 * experience override column, then an active EXPERIENCE-scoped rule, then the host
 * override column / HOST rule, then CATEGORY, CITY, PLATFORM rules, then the config
 * default. Time-boxed promos live as rules at the appropriate scope.
 */
@Service
public class CommissionResolver {

    private final CommissionRuleRepository commissionRuleRepository;
    private final BigDecimal configDefaultRate;

    public CommissionResolver(CommissionRuleRepository commissionRuleRepository,
                              @Value("${app.platform.commission-percentage:20}") BigDecimal commissionPercentage) {
        this.commissionRuleRepository = commissionRuleRepository;
        this.configDefaultRate = commissionPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal resolve(Experience experience, LocalProfile host, Instant now) {
        if (experience.getCommissionRate() != null) {
            return experience.getCommissionRate();
        }
        BigDecimal rate = firstRate(commissionRuleRepository.findActive(ScopeType.EXPERIENCE, experience.getId(), now));
        if (rate != null) {
            return rate;
        }

        if (host != null && host.getCommissionRate() != null) {
            return host.getCommissionRate();
        }
        if (host != null) {
            rate = firstRate(commissionRuleRepository.findActive(ScopeType.HOST, host.getId(), now));
            if (rate != null) {
                return rate;
            }
        }

        if (experience.getCategory() != null) {
            rate = firstRate(commissionRuleRepository.findActive(ScopeType.CATEGORY, experience.getCategory().getId(), now));
            if (rate != null) {
                return rate;
            }
        }

        if (experience.getCity() != null) {
            rate = firstRate(commissionRuleRepository.findActive(ScopeType.CITY, experience.getCity().getId(), now));
            if (rate != null) {
                return rate;
            }
        }

        rate = firstRate(commissionRuleRepository.findActive(ScopeType.PLATFORM, null, now));
        return rate != null ? rate : configDefaultRate;
    }

    private BigDecimal firstRate(List<CommissionRule> rules) {
        return rules.isEmpty() ? null : rules.get(0).getRate();
    }
}
