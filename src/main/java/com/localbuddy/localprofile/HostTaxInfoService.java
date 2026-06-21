package com.localbuddy.localprofile;

import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Captures a host's VAT / DAC7 tax information. */
@Service
public class HostTaxInfoService {

    private final LocalProfileRepository localProfileRepository;

    public HostTaxInfoService(LocalProfileRepository localProfileRepository) {
        this.localProfileRepository = localProfileRepository;
    }

    @Transactional(readOnly = true)
    public HostTaxInfoResponse getForUser(UUID userId) {
        return toResponse(requireProfile(userId));
    }

    @Transactional
    public HostTaxInfoResponse update(UUID userId, HostTaxInfoRequest request) {
        LocalProfile host = requireProfile(userId);

        if (request.vatRegistered() != null) {
            host.setVatRegistered(request.vatRegistered());
        }
        host.setVatNumber(trim(request.vatNumber()));
        host.setTaxCountry(upper(request.taxCountry()));
        host.setLegalEntityType(trim(request.legalEntityType()));
        host.setTaxIdentificationNumber(trim(request.taxIdentificationNumber()));
        host.setBusinessRegistrationNumber(trim(request.businessRegistrationNumber()));
        host.setDateOfBirth(request.dateOfBirth());

        return toResponse(localProfileRepository.save(host));
    }

    private LocalProfile requireProfile(UUID userId) {
        return localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));
    }

    private HostTaxInfoResponse toResponse(LocalProfile host) {
        return new HostTaxInfoResponse(
                host.isVatRegistered(), host.getVatNumber(), host.getTaxCountry(),
                host.getLegalEntityType(), host.getTaxIdentificationNumber(),
                host.getBusinessRegistrationNumber(), host.getDateOfBirth());
    }

    private static String trim(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String upper(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase();
    }
}
