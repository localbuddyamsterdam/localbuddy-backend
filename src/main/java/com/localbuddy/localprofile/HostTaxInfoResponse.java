package com.localbuddy.localprofile;

import java.time.LocalDate;

public record HostTaxInfoResponse(
        boolean vatRegistered,
        String vatNumber,
        String taxCountry,
        String legalEntityType,
        String taxIdentificationNumber,
        String businessRegistrationNumber,
        LocalDate dateOfBirth
) {
}
