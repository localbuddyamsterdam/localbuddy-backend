package com.localbuddy.pricing;

import com.localbuddy.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the admin rate-management guardrails: the commission cap (which closes
 * the negative-payout edge surfaced by {@link PricingInvariantPropertyTest}) and
 * the before/after audit trail.
 */
@SpringBootTest
@Transactional
class RateAdminServiceIT {

    @Autowired
    RateAdminService rateAdminService;

    @Autowired
    RateChangeAuditRepository auditRepository;

    @Test
    void createCommissionRule_succeedsAndWritesCreationAudit() {
        UUID admin = UUID.randomUUID();
        CommissionRuleResponse resp = rateAdminService.createCommissionRule(
                new CreateCommissionRuleRequest(ScopeType.PLATFORM, null, new BigDecimal("0.15"), null, null, "Jan promo"),
                admin);

        assertThat(resp.active()).isTrue();
        assertThat(resp.rate()).isEqualByComparingTo("0.15");

        assertThat(auditRepository.findByRateTypeOrderByChangedAtDesc("COMMISSION"))
                .anyMatch(a -> resp.id().equals(a.getRuleId())
                        && a.getOldValue() == null
                        && a.getNewValue() != null
                        && admin.equals(a.getChangedByUserId()));
    }

    @Test
    void createCommissionRule_aboveCap_isRejected() {
        assertThatThrownBy(() -> rateAdminService.createCommissionRule(
                new CreateCommissionRuleRequest(ScopeType.HOST, UUID.randomUUID(), new BigDecimal("0.60"), null, null, null),
                UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void deactivateCommissionRule_writesBeforeAndAfterAudit() {
        UUID admin = UUID.randomUUID();
        CommissionRuleResponse created = rateAdminService.createCommissionRule(
                new CreateCommissionRuleRequest(ScopeType.CITY, UUID.randomUUID(), new BigDecimal("0.18"), null, null, null),
                admin);

        CommissionRuleResponse deactivated = rateAdminService.deactivateCommissionRule(created.id(), admin);
        assertThat(deactivated.active()).isFalse();

        assertThat(auditRepository.findByRateTypeOrderByChangedAtDesc("COMMISSION"))
                .anyMatch(a -> created.id().equals(a.getRuleId())
                        && a.getOldValue() != null
                        && Boolean.TRUE.equals(a.getOldValue().get("active"))
                        && Boolean.FALSE.equals(a.getNewValue().get("active")));
    }

    @Test
    void createVatRate_normalizesCountryAndPersists() {
        VatRateResponse resp = rateAdminService.createVatRate(
                new CreateVatRateRequest("nl", null, new BigDecimal("0.09"), "reduced", "Reduced rate", null, null),
                UUID.randomUUID());

        assertThat(resp.country()).isEqualTo("NL");
        assertThat(resp.rateKind()).isEqualTo("REDUCED");
        assertThat(resp.rate()).isEqualByComparingTo("0.09");
    }
}
