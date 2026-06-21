package com.localbuddy.pricing;

import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves VAT inputs: the host's VAT status, the place of supply, the experience
 * VAT rate (place of supply x category x date), and the rate applied to the
 * platform's own fees (your home-country standard rate).
 */
@Service
public class VatService {

    private static final Set<String> EU_COUNTRIES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK",
            "SI", "ES", "SE");

    private final VatRateRepository vatRateRepository;
    private final String homeCountry;
    private final BigDecimal configStandardRate;

    public VatService(VatRateRepository vatRateRepository,
                      @Value("${app.vat.default-country:NL}") String homeCountry,
                      @Value("${app.vat.standard-rate-percentage:21}") BigDecimal standardRatePercentage) {
        this.vatRateRepository = vatRateRepository;
        this.homeCountry = homeCountry.toUpperCase();
        this.configStandardRate = standardRatePercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    /**
     * Place of supply for the experience VAT. For services tied to a location this
     * is where the experience happens; until per-city ISO mapping is added this
     * defaults to the platform home country.
     */
    public String placeOfSupply(Experience experience, LocalProfile host) {
        return homeCountry;
    }

    public HostVatStatus hostVatStatus(LocalProfile host) {
        String country = (host != null && host.getTaxCountry() != null && !host.getTaxCountry().isBlank())
                ? host.getTaxCountry().toUpperCase()
                : homeCountry;
        boolean registered = host != null && host.isVatRegistered();
        boolean inEu = EU_COUNTRIES.contains(country);

        if (registered) {
            if (country.equals(homeCountry)) {
                return HostVatStatus.NL_REGISTERED;
            }
            return inEu ? HostVatStatus.EU_OTHER_REGISTERED : HostVatStatus.NON_EU;
        }
        return inEu ? HostVatStatus.NL_NOT_REGISTERED : HostVatStatus.NON_EU;
    }

    public BigDecimal experienceVatRate(Experience experience, String country, Instant now) {
        UUID categoryId = experience.getCategory() != null ? experience.getCategory().getId() : null;
        if (categoryId != null) {
            List<VatRate> categoryRates = vatRateRepository.findActive(country, categoryId, now);
            if (!categoryRates.isEmpty()) {
                return categoryRates.get(0).getRate();
            }
        }
        return standardRateForCountry(country, now);
    }

    /** VAT applied to the platform's own fees (commission, service fee) = home-country standard rate. */
    public BigDecimal feeVatRate(Instant now) {
        return standardRateForCountry(homeCountry, now);
    }

    private BigDecimal standardRateForCountry(String country, Instant now) {
        return vatRateRepository.findActive(country, null, now).stream()
                .filter(v -> "STANDARD".equalsIgnoreCase(v.getRateKind()))
                .findFirst()
                .map(VatRate::getRate)
                .orElse(configStandardRate);
    }
}
