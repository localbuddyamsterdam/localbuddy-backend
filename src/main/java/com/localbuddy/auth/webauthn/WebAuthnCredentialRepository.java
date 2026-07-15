package com.localbuddy.auth.webauthn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredential, UUID> {

    Optional<WebAuthnCredential> findByCredentialId(String credentialId);

    List<WebAuthnCredential> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<WebAuthnCredential> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserId(UUID userId);
}
