package com.localbuddy.auth.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A registered passkey (WebAuthn credential) — the public half of a key pair held by the user's
 * device (Face ID / Touch ID / Windows Hello / phone passcode). The private key never leaves the
 * authenticator; we verify its signatures with {@code publicKey} at sign-in.
 */
@Entity
@Table(name = "webauthn_credentials")
@Getter
@Setter
@NoArgsConstructor
public class WebAuthnCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Credential id as minted by the authenticator, base64url (no padding). */
    @Column(name = "credential_id", nullable = false, unique = true)
    private String credentialId;

    /** SubjectPublicKeyInfo (SPKI) DER, base64 — decodable with plain {@code X509EncodedKeySpec}. */
    @Column(name = "public_key", nullable = false)
    private String publicKey;

    /** COSE algorithm identifier: -7 ES256, -257 RS256, -8 Ed25519. */
    @Column(name = "algorithm", nullable = false)
    private int algorithm;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    /** Comma-joined transport hints ("internal,hybrid") — echoed back in allowCredentials. */
    @Column(name = "transports")
    private String transports;

    /** User-facing name shown in account settings ("iPhone", "This Mac", ...). */
    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
