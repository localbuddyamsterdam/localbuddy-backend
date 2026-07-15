package com.localbuddy.auth.webauthn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.UUID;

public interface WebAuthnChallengeRepository extends JpaRepository<WebAuthnChallenge, UUID> {

    @Modifying
    void deleteByExpiresAtBefore(Instant cutoff);
}
