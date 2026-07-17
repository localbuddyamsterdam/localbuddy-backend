package com.localbuddy.pricing;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin management of the rate tables (commission / service fee / VAT) plus the
 * {@link RateChangeAudit} trail. Every mutation records a before/after snapshot.
 */
@Service
public class RateAdminService {

    private final CommissionRuleRepository commissionRuleRepository;
    private final ServiceFeeRuleRepository serviceFeeRuleRepository;
    private final VatRateRepository vatRateRepository;
    private final RateChangeAuditRepository auditRepository;
    private final BigDecimal maxCommissionRate;

    public RateAdminService(CommissionRuleRepository commissionRuleRepository,
                            ServiceFeeRuleRepository serviceFeeRuleRepository,
                            VatRateRepository vatRateRepository,
                            RateChangeAuditRepository auditRepository,
                            @Value("${app.platform.max-commission-rate:0.50}") BigDecimal maxCommissionRate) {
        this.commissionRuleRepository = commissionRuleRepository;
        this.serviceFeeRuleRepository = serviceFeeRuleRepository;
        this.vatRateRepository = vatRateRepository;
        this.auditRepository = auditRepository;
        this.maxCommissionRate = maxCommissionRate;
    }

    // ---------------------------------------------------------------- Commission

    @Transactional
    public CommissionRuleResponse createCommissionRule(CreateCommissionRuleRequest req, UUID adminId) {
        validateRate(req.rate());
        if (req.rate().compareTo(maxCommissionRate) > 0) {
            throw new BadRequestException(
                    "Commission rate " + req.rate() + " exceeds the maximum allowed (" + maxCommissionRate
                            + "). A higher rate could drive a host payout negative once commission VAT is added.");
        }
        validateWindow(req.effectiveFrom(), req.effectiveTo());

        CommissionRule rule = new CommissionRule();
        rule.setScopeType(req.scopeType());
        rule.setScopeId(req.scopeId());
        rule.setRate(req.rate());
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        rule.setActive(true);
        rule.setNote(req.note());
        rule.setCreatedByUserId(adminId);
        CommissionRule saved = commissionRuleRepository.save(rule);

        audit("COMMISSION", saved.getId(), null, commissionSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional
    public CommissionRuleResponse deactivateCommissionRule(UUID id, UUID adminId) {
        CommissionRule rule = commissionRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commission rule not found: " + id));
        Map<String, Object> before = commissionSnapshot(rule);
        rule.setActive(false);
        CommissionRule saved = commissionRuleRepository.save(rule);
        audit("COMMISSION", id, before, commissionSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional
    public CommissionRuleResponse updateCommissionRule(UUID id, UpdateCommissionRuleRequest req, UUID adminId) {
        CommissionRule rule = commissionRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commission rule not found: " + id));
        Map<String, Object> before = commissionSnapshot(rule);

        validateRate(req.rate());
        if (req.rate().compareTo(maxCommissionRate) > 0) {
            throw new BadRequestException(
                    "Commission rate " + req.rate() + " exceeds the maximum allowed (" + maxCommissionRate
                            + "). A higher rate could drive a host payout negative once commission VAT is added.");
        }
        validateWindow(req.effectiveFrom(), req.effectiveTo());

        rule.setRate(req.rate());
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        rule.setNote(req.note());
        if (req.active() != null) {
            rule.setActive(req.active());
        }
        CommissionRule saved = commissionRuleRepository.save(rule);
        audit("COMMISSION", id, before, commissionSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CommissionRuleResponse> listCommissionRules() {
        return commissionRuleRepository.findAll().stream().map(this::toResponse).toList();
    }

    // -------------------------------------------------------------- Service fee

    @Transactional
    public ServiceFeeRuleResponse createServiceFeeRule(CreateServiceFeeRuleRequest req, UUID adminId) {
        validateRate(req.rate());
        validateWindow(req.effectiveFrom(), req.effectiveTo());

        ServiceFeeRule rule = new ServiceFeeRule();
        rule.setScopeType(req.scopeType());
        rule.setScopeId(req.scopeId());
        rule.setRate(req.rate());
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        rule.setActive(true);
        rule.setNote(req.note());
        rule.setCreatedByUserId(adminId);
        ServiceFeeRule saved = serviceFeeRuleRepository.save(rule);

        audit("SERVICE_FEE", saved.getId(), null, serviceFeeSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional
    public ServiceFeeRuleResponse updateServiceFeeRule(UUID id, UpdateServiceFeeRuleRequest req, UUID adminId) {
        ServiceFeeRule rule = serviceFeeRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service fee rule not found: " + id));
        Map<String, Object> before = serviceFeeSnapshot(rule);

        validateRate(req.rate());
        validateWindow(req.effectiveFrom(), req.effectiveTo());

        rule.setRate(req.rate());
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        rule.setNote(req.note());
        if (req.active() != null) {
            rule.setActive(req.active());
        }
        ServiceFeeRule saved = serviceFeeRuleRepository.save(rule);
        audit("SERVICE_FEE", id, before, serviceFeeSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional
    public ServiceFeeRuleResponse deactivateServiceFeeRule(UUID id, UUID adminId) {
        ServiceFeeRule rule = serviceFeeRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service fee rule not found: " + id));
        Map<String, Object> before = serviceFeeSnapshot(rule);
        rule.setActive(false);
        ServiceFeeRule saved = serviceFeeRuleRepository.save(rule);
        audit("SERVICE_FEE", id, before, serviceFeeSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ServiceFeeRuleResponse> listServiceFeeRules() {
        return serviceFeeRuleRepository.findAll().stream().map(this::toResponse).toList();
    }

    // --------------------------------------------------------------------- VAT

    @Transactional
    public VatRateResponse createVatRate(CreateVatRateRequest req, UUID adminId) {
        validateRate(req.rate());
        validateWindow(req.effectiveFrom(), req.effectiveTo());

        VatRate rate = new VatRate();
        rate.setCountry(req.country().toUpperCase());
        rate.setCategoryId(req.categoryId());
        rate.setRate(req.rate());
        if (req.rateKind() != null && !req.rateKind().isBlank()) {
            rate.setRateKind(req.rateKind().toUpperCase());
        }
        rate.setDescription(req.description());
        rate.setEffectiveFrom(req.effectiveFrom());
        rate.setEffectiveTo(req.effectiveTo());
        rate.setActive(true);
        VatRate saved = vatRateRepository.save(rate);

        audit("VAT", saved.getId(), null, vatSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional
    public VatRateResponse deactivateVatRate(UUID id, UUID adminId) {
        VatRate rate = vatRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("VAT rate not found: " + id));
        Map<String, Object> before = vatSnapshot(rate);
        rate.setActive(false);
        VatRate saved = vatRateRepository.save(rate);
        audit("VAT", id, before, vatSnapshot(saved), adminId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<VatRateResponse> listVatRates() {
        return vatRateRepository.findAll().stream().map(this::toResponse).toList();
    }

    // ------------------------------------------------------------------- Audit

    @Transactional(readOnly = true)
    public List<RateChangeAudit> auditTrail(String rateType) {
        if (rateType == null || rateType.isBlank()) {
            return auditRepository.findAll();
        }
        return auditRepository.findByRateTypeOrderByChangedAtDesc(rateType.toUpperCase());
    }

    // ----------------------------------------------------------------- Helpers

    private void validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0) {
            throw new BadRequestException("Rate must be zero or positive");
        }
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BadRequestException("Rate must be expressed as a fraction (0.00–1.00), e.g. 0.20 for 20%");
        }
    }

    private void validateWindow(java.time.Instant from, java.time.Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BadRequestException("effectiveTo must be after effectiveFrom");
        }
    }

    private void audit(String type, UUID ruleId, Map<String, Object> before, Map<String, Object> after, UUID adminId) {
        RateChangeAudit entry = new RateChangeAudit();
        entry.setRateType(type);
        entry.setRuleId(ruleId);
        entry.setOldValue(before);
        entry.setNewValue(after);
        entry.setChangedByUserId(adminId);
        auditRepository.save(entry);
    }

    private Map<String, Object> commissionSnapshot(CommissionRule r) {
        Map<String, Object> m = new HashMap<>();
        m.put("scopeType", r.getScopeType() == null ? null : r.getScopeType().name());
        m.put("scopeId", r.getScopeId() == null ? null : r.getScopeId().toString());
        m.put("rate", r.getRate());
        m.put("effectiveFrom", r.getEffectiveFrom() == null ? null : r.getEffectiveFrom().toString());
        m.put("effectiveTo", r.getEffectiveTo() == null ? null : r.getEffectiveTo().toString());
        m.put("active", r.isActive());
        return m;
    }

    private Map<String, Object> serviceFeeSnapshot(ServiceFeeRule r) {
        Map<String, Object> m = new HashMap<>();
        m.put("scopeType", r.getScopeType() == null ? null : r.getScopeType().name());
        m.put("scopeId", r.getScopeId() == null ? null : r.getScopeId().toString());
        m.put("rate", r.getRate());
        m.put("effectiveFrom", r.getEffectiveFrom() == null ? null : r.getEffectiveFrom().toString());
        m.put("effectiveTo", r.getEffectiveTo() == null ? null : r.getEffectiveTo().toString());
        m.put("active", r.isActive());
        return m;
    }

    private Map<String, Object> vatSnapshot(VatRate r) {
        Map<String, Object> m = new HashMap<>();
        m.put("country", r.getCountry());
        m.put("categoryId", r.getCategoryId() == null ? null : r.getCategoryId().toString());
        m.put("rate", r.getRate());
        m.put("rateKind", r.getRateKind());
        m.put("effectiveFrom", r.getEffectiveFrom() == null ? null : r.getEffectiveFrom().toString());
        m.put("effectiveTo", r.getEffectiveTo() == null ? null : r.getEffectiveTo().toString());
        m.put("active", r.isActive());
        return m;
    }

    private CommissionRuleResponse toResponse(CommissionRule r) {
        return new CommissionRuleResponse(r.getId(), r.getScopeType(), r.getScopeId(), r.getRate(),
                r.getEffectiveFrom(), r.getEffectiveTo(), r.isActive(), r.getNote());
    }

    private ServiceFeeRuleResponse toResponse(ServiceFeeRule r) {
        return new ServiceFeeRuleResponse(r.getId(), r.getScopeType(), r.getScopeId(), r.getRate(),
                r.getEffectiveFrom(), r.getEffectiveTo(), r.isActive(), r.getNote());
    }

    private VatRateResponse toResponse(VatRate r) {
        return new VatRateResponse(r.getId(), r.getCountry(), r.getCategoryId(), r.getRate(), r.getRateKind(),
                r.getDescription(), r.getEffectiveFrom(), r.getEffectiveTo(), r.isActive());
    }
}
