package com.localbuddy.invoice;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanySettingsService {

    private final CompanySettingsRepository companySettingsRepository;

    public CompanySettingsService(CompanySettingsRepository companySettingsRepository) {
        this.companySettingsRepository = companySettingsRepository;
    }

    @Transactional(readOnly = true)
    public CompanySettingsResponse get() {
        return companySettingsRepository.findFirstByActiveTrueOrderByUpdatedAtDesc()
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public CompanySettingsResponse upsert(UpsertCompanySettingsRequest request) {
        CompanySettings settings = companySettingsRepository.findFirstByActiveTrueOrderByUpdatedAtDesc()
                .orElseGet(CompanySettings::new);

        settings.setActive(true);
        settings.setLegalName(request.legalName());
        settings.setTradingName(request.tradingName());
        settings.setVatNumber(request.vatNumber());
        settings.setCocNumber(request.cocNumber());
        settings.setAddressLine1(request.addressLine1());
        settings.setAddressLine2(request.addressLine2());
        settings.setPostalCode(request.postalCode());
        settings.setCity(request.city());
        if (request.country() != null && !request.country().isBlank()) {
            settings.setCountry(request.country().toUpperCase());
        }
        settings.setEmail(request.email());
        settings.setPhone(request.phone());
        settings.setIban(request.iban());
        if (request.invoiceNumberPrefix() != null && !request.invoiceNumberPrefix().isBlank()) {
            settings.setInvoiceNumberPrefix(request.invoiceNumberPrefix());
        }
        settings.setInvoiceFooter(request.invoiceFooter());

        return toResponse(companySettingsRepository.save(settings));
    }

    private CompanySettingsResponse toResponse(CompanySettings c) {
        return new CompanySettingsResponse(
                c.getId(), c.getLegalName(), c.getTradingName(), c.getVatNumber(), c.getCocNumber(),
                c.getAddressLine1(), c.getAddressLine2(), c.getPostalCode(), c.getCity(), c.getCountry(),
                c.getEmail(), c.getPhone(), c.getIban(), c.getInvoiceNumberPrefix(), c.getInvoiceFooter());
    }
}
