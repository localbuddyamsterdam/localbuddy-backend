package com.localbuddy.localprofile;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Host VAT + DAC7 tax details. */
public record HostTaxInfoRequest(
        Boolean vatRegistered,
        @Size(max = 40) String vatNumber,
        @Size(max = 2) String taxCountry,
        @Size(max = 20) String legalEntityType,
        @Size(max = 60) String taxIdentificationNumber,
        @Size(max = 60) String businessRegistrationNumber,
        LocalDate dateOfBirth
) {
}
