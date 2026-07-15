-- Passkey (WebAuthn) sign-in: registered platform-authenticator credentials
-- (Face ID / Touch ID / Windows Hello / phone passcode) plus the short-lived,
-- single-use challenges both ceremonies verify against.

CREATE TABLE webauthn_credentials (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id  TEXT NOT NULL UNIQUE,           -- base64url, as minted by the authenticator
    public_key     TEXT NOT NULL,                  -- base64 SPKI (SubjectPublicKeyInfo DER)
    algorithm      INTEGER NOT NULL,               -- COSE alg: -7 ES256, -257 RS256, -8 Ed25519
    sign_count     BIGINT NOT NULL DEFAULT 0,
    transports     TEXT,                           -- comma-joined AuthenticatorTransport hints
    label          VARCHAR(120),                   -- user-facing name ("iPhone", "MacBook", ...)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at   TIMESTAMPTZ
);
CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials(user_id);

CREATE TABLE webauthn_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    challenge   VARCHAR(120) NOT NULL,             -- base64url random bytes
    user_id     UUID,                              -- null for a userless (discoverable) login
    purpose     VARCHAR(20) NOT NULL,              -- REGISTRATION | AUTHENTICATION
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
