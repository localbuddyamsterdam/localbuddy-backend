package com.localbuddy.auth.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived, single-use WebAuthn ceremony challenge. The client echoes it back inside the
 * signed {@code clientDataJSON}, proving the response was produced for this specific request
 * (anti-replay). {@code userId} is null for a userless (discoverable-credential) login.
 */
@Entity
@Table(name = "webauthn_challenges")
@Getter
@Setter
@NoArgsConstructor
public class WebAuthnChallenge {

    public enum Purpose { REGISTRATION, AUTHENTICATION }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Random challenge bytes, base64url (no padding). */
    @Column(name = "challenge", nullable = false, length = 120)
    private String challenge;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private Purpose purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
