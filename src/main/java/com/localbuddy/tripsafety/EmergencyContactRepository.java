package com.localbuddy.tripsafety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, UUID> {

    Optional<EmergencyContact> findByUserId(UUID userId);
}
