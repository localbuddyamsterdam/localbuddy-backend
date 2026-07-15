package com.localbuddy.auth.webauthn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.auth.AuthService;
import com.localbuddy.auth.LoginResponse;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.UnauthorizedException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Passkey (WebAuthn) sign-in without a WebAuthn library: the client sends the browser-extracted
 * SPKI public key at enrollment ({@code AuthenticatorAttestationResponse.getPublicKey()}), so both
 * ceremonies verify with plain JDK crypto — challenge/origin/rpId checks on {@code clientDataJSON}
 * and {@code authenticatorData}, then a signature check over
 * {@code authenticatorData || SHA-256(clientDataJSON)} at sign-in. Attestation is not validated
 * (equivalent to attestation "none", the industry default); enrollment requires an authenticated
 * session, which is the actual trust anchor.
 */
@Service
public class WebAuthnService {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);

    /** COSE algorithms we accept: ES256, RS256, Ed25519. */
    private static final Set<Integer> SUPPORTED_ALGORITHMS = Set.of(-7, -257, -8);

    private static final int FLAG_USER_PRESENT = 0x01;
    private static final int FLAG_USER_VERIFIED = 0x04;

    private final WebAuthnCredentialRepository credentialRepository;
    private final WebAuthnChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    private final String rpId;
    private final String rpName;
    private final Set<String> allowedOrigins;
    private final long challengeExpirationMinutes;

    public WebAuthnService(WebAuthnCredentialRepository credentialRepository,
                           WebAuthnChallengeRepository challengeRepository,
                           UserRepository userRepository,
                           AuthService authService,
                           ObjectMapper objectMapper,
                           @Value("${app.security.webauthn.rp-id:}") String configuredRpId,
                           @Value("${app.security.webauthn.allowed-origins:}") String configuredOrigins,
                           @Value("${app.security.webauthn.rp-name:LocalBuddy}") String rpName,
                           @Value("${app.security.webauthn.challenge-expiration-minutes:5}") long challengeExpirationMinutes,
                           @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.credentialRepository = credentialRepository;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.rpName = rpName;
        this.challengeExpirationMinutes = challengeExpirationMinutes;

        // Both default from the frontend URL (passkeys are scoped to the site the browser shows):
        // rp-id is its host, allowed origins its origin. Local dev additionally allows the
        // ng-serve ports, but only when the rp is localhost — never in production.
        URI frontend = URI.create(frontendBaseUrl);
        this.rpId = configuredRpId.isBlank() ? frontend.getHost() : configuredRpId.trim();
        Set<String> origins = new LinkedHashSet<>();
        if (configuredOrigins.isBlank()) {
            origins.add(originOf(frontend));
        } else {
            for (String origin : configuredOrigins.split(",")) {
                if (!origin.isBlank()) {
                    origins.add(origin.trim());
                }
            }
        }
        if ("localhost".equals(this.rpId)) {
            origins.add("http://localhost:3000");
            origins.add("http://localhost:4200");
        }
        this.allowedOrigins = origins;
    }

    private static String originOf(URI uri) {
        String origin = uri.getScheme() + "://" + uri.getHost();
        if (uri.getPort() != -1) {
            origin += ":" + uri.getPort();
        }
        return origin;
    }

    // ------------------------------------------------------------------ enrollment

    /** Issues a registration challenge for the authenticated user. */
    @Transactional
    public WebAuthnRegistrationOptionsResponse registrationOptions(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        WebAuthnChallenge challenge = issueChallenge(userId, WebAuthnChallenge.Purpose.REGISTRATION);
        List<String> exclude = credentialRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(WebAuthnCredential::getCredentialId)
                .toList();

        String displayName = (user.getPreferredName() != null && !user.getPreferredName().isBlank())
                ? user.getPreferredName()
                : (user.getFirstName() + " " + user.getLastName()).trim();

        return new WebAuthnRegistrationOptionsResponse(
                challenge.getId().toString(),
                challenge.getChallenge(),
                rpId,
                rpName,
                userHandleFor(userId),
                user.getEmail(),
                displayName.isBlank() ? user.getEmail() : displayName,
                exclude
        );
    }

    /** Verifies and stores a new passkey for the authenticated user. */
    @Transactional
    public WebAuthnCredentialResponse completeRegistration(UUID userId, WebAuthnRegisterRequest request) {
        WebAuthnChallenge challenge = consumeChallenge(request.challengeId(), WebAuthnChallenge.Purpose.REGISTRATION);
        if (!userId.equals(challenge.getUserId())) {
            throw new BadRequestException("This enrollment request belongs to a different session");
        }

        verifyClientData(request.clientDataJson(), "webauthn.create", challenge.getChallenge());
        byte[] authData = decodeUrl(request.authenticatorData(), "authenticatorData");
        verifyAuthenticatorData(authData, true);

        if (!SUPPORTED_ALGORITHMS.contains(request.algorithm())) {
            throw new BadRequestException("Unsupported passkey algorithm");
        }
        // Decode now so a malformed key is rejected at enrollment, not at first sign-in.
        decodePublicKey(request.publicKey(), request.algorithm());

        if (credentialRepository.findByCredentialId(request.credentialId()).isPresent()) {
            throw new BadRequestException("This passkey is already registered");
        }

        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setUserId(userId);
        credential.setCredentialId(request.credentialId());
        credential.setPublicKey(request.publicKey());
        credential.setAlgorithm(request.algorithm());
        credential.setSignCount(parseSignCount(authData));
        credential.setTransports(request.transports() == null ? null : String.join(",", request.transports()));
        credential.setLabel(request.label() == null || request.label().isBlank()
                ? "Passkey" : request.label().trim());
        credential = credentialRepository.save(credential);

        return toResponse(credential);
    }

    // ------------------------------------------------------------------ sign-in

    /**
     * Issues a sign-in challenge. With an email, the response carries that account's credential
     * ids; without one (or for an unknown email) it is a userless ceremony — the response looks
     * identical either way, so this reveals nothing new about account existence beyond what
     * check-email already does.
     */
    @Transactional
    public WebAuthnAuthenticationOptionsResponse authenticationOptions(String email) {
        UUID userId = null;
        List<String> allowCredentialIds = List.of();
        if (email != null && !email.isBlank()) {
            User user = userRepository.findByEmail(email.trim().toLowerCase()).orElse(null);
            if (user != null) {
                userId = user.getId();
                allowCredentialIds = credentialRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(WebAuthnCredential::getCredentialId)
                        .toList();
            }
        }
        WebAuthnChallenge challenge = issueChallenge(userId, WebAuthnChallenge.Purpose.AUTHENTICATION);
        return new WebAuthnAuthenticationOptionsResponse(
                challenge.getId().toString(),
                challenge.getChallenge(),
                rpId,
                allowCredentialIds
        );
    }

    /** Verifies a passkey assertion and signs the user in. */
    @Transactional
    public LoginResponse completeAuthentication(WebAuthnLoginRequest request) {
        WebAuthnChallenge challenge = consumeChallenge(request.challengeId(), WebAuthnChallenge.Purpose.AUTHENTICATION);

        WebAuthnCredential credential = credentialRepository.findByCredentialId(request.credentialId())
                .orElseThrow(() -> new UnauthorizedException("Passkey sign-in failed"));

        // An email-scoped challenge must be answered by that account's passkey.
        if (challenge.getUserId() != null && !challenge.getUserId().equals(credential.getUserId())) {
            throw new UnauthorizedException("Passkey sign-in failed");
        }
        // A discoverable credential reports its user handle — it must match the credential owner.
        if (request.userHandle() != null && !request.userHandle().isBlank()
                && !request.userHandle().equals(userHandleFor(credential.getUserId()))) {
            throw new UnauthorizedException("Passkey sign-in failed");
        }

        byte[] clientDataJson = verifyClientData(request.clientDataJson(), "webauthn.get", challenge.getChallenge());
        byte[] authData = decodeUrl(request.authenticatorData(), "authenticatorData");
        verifyAuthenticatorData(authData, false);

        // signedData = authenticatorData || SHA-256(clientDataJSON)
        byte[] signedData = new byte[authData.length + 32];
        System.arraycopy(authData, 0, signedData, 0, authData.length);
        System.arraycopy(sha256(clientDataJson), 0, signedData, authData.length, 32);

        PublicKey publicKey = decodePublicKey(credential.getPublicKey(), credential.getAlgorithm());
        if (!verifySignature(publicKey, credential.getAlgorithm(), signedData,
                decodeUrl(request.signature(), "signature"))) {
            throw new UnauthorizedException("Passkey sign-in failed");
        }

        User user = userRepository.findById(credential.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Passkey sign-in failed"));
        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Account is not active");
        }

        // Signature counters only ever grow on authenticators that maintain one (many platform
        // authenticators always report 0). A regression suggests a cloned key — log it, since with
        // synced passkeys (iCloud/Google) legitimate multi-device use can also trip this.
        long newCount = parseSignCount(authData);
        if (newCount != 0 && newCount <= credential.getSignCount()) {
            log.warn("Passkey sign-count did not increase for credential {} (stored={}, presented={})",
                    credential.getId(), credential.getSignCount(), newCount);
        }
        credential.setSignCount(Math.max(newCount, credential.getSignCount()));
        credential.setLastUsedAt(Instant.now());
        credentialRepository.save(credential);

        return authService.issueSessionFor(user);
    }

    // ------------------------------------------------------------------ management

    @Transactional(readOnly = true)
    public List<WebAuthnCredentialResponse> listCredentials(UUID userId) {
        return credentialRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteCredential(UUID userId, UUID credentialId) {
        WebAuthnCredential credential = credentialRepository.findByIdAndUserId(credentialId, userId)
                .orElseThrow(() -> new BadRequestException("Passkey not found"));
        credentialRepository.delete(credential);
    }

    // ------------------------------------------------------------------ verification internals

    private WebAuthnChallenge issueChallenge(UUID userId, WebAuthnChallenge.Purpose purpose) {
        // Opportunistically clear out stale rows so the table can't grow unbounded.
        challengeRepository.deleteByExpiresAtBefore(Instant.now().minus(Duration.ofHours(1)));

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        WebAuthnChallenge challenge = new WebAuthnChallenge();
        challenge.setChallenge(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        challenge.setUserId(userId);
        challenge.setPurpose(purpose);
        challenge.setExpiresAt(Instant.now().plus(Duration.ofMinutes(challengeExpirationMinutes)));
        challenge.setUsed(false);
        return challengeRepository.save(challenge);
    }

    private WebAuthnChallenge consumeChallenge(String challengeId, WebAuthnChallenge.Purpose purpose) {
        UUID id;
        try {
            id = UUID.fromString(challengeId);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("This sign-in attempt has expired — please try again");
        }
        WebAuthnChallenge challenge = challengeRepository.findById(id)
                .filter(c -> c.getPurpose() == purpose)
                .filter(c -> !c.isUsed())
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new UnauthorizedException("This sign-in attempt has expired — please try again"));
        challenge.setUsed(true);
        return challengeRepository.save(challenge);
    }

    /** Checks type / challenge / origin on clientDataJSON; returns its raw bytes for hashing. */
    private byte[] verifyClientData(String clientDataJsonB64, String expectedType, String expectedChallenge) {
        byte[] raw = decodeUrl(clientDataJsonB64, "clientDataJSON");
        JsonNode clientData;
        try {
            clientData = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new UnauthorizedException("Passkey verification failed");
        }
        String type = clientData.path("type").asText("");
        String challenge = clientData.path("challenge").asText("");
        String origin = clientData.path("origin").asText("");

        if (!expectedType.equals(type)) {
            throw new UnauthorizedException("Passkey verification failed");
        }
        if (!MessageDigest.isEqual(
                challenge.getBytes(StandardCharsets.UTF_8),
                expectedChallenge.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Passkey verification failed");
        }
        if (!allowedOrigins.contains(origin)) {
            log.warn("WebAuthn origin {} not in allowed set {}", origin, allowedOrigins);
            throw new UnauthorizedException("Passkey verification failed");
        }
        return raw;
    }

    /**
     * Structural checks on authenticator data: correct rp-id hash, user present, and user
     * verified (Face ID / fingerprint / device passcode — both ceremonies request UV, so a
     * possession-only tap can't sign in without the device unlock step).
     */
    private void verifyAuthenticatorData(byte[] authData, boolean registration) {
        if (authData.length < 37) {
            throw new UnauthorizedException("Passkey verification failed");
        }
        byte[] rpIdHash = new byte[32];
        System.arraycopy(authData, 0, rpIdHash, 0, 32);
        if (!MessageDigest.isEqual(rpIdHash, sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
            throw new UnauthorizedException("Passkey verification failed");
        }
        int flags = authData[32] & 0xFF;
        if ((flags & FLAG_USER_PRESENT) == 0 || (flags & FLAG_USER_VERIFIED) == 0) {
            String message = registration
                    ? "This device didn't confirm it's you (Face ID, fingerprint or passcode) — please try again"
                    : "Passkey verification failed";
            throw new UnauthorizedException(message);
        }
    }

    private long parseSignCount(byte[] authData) {
        return ((authData[33] & 0xFFL) << 24)
                | ((authData[34] & 0xFFL) << 16)
                | ((authData[35] & 0xFFL) << 8)
                | (authData[36] & 0xFFL);
    }

    private PublicKey decodePublicKey(String spkiBase64, int algorithm) {
        String keyFactory = switch (algorithm) {
            case -7 -> "EC";
            case -257 -> "RSA";
            case -8 -> "Ed25519";
            default -> throw new BadRequestException("Unsupported passkey algorithm");
        };
        try {
            byte[] der = Base64.getDecoder().decode(spkiBase64);
            return KeyFactory.getInstance(keyFactory).generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new BadRequestException("Invalid passkey public key");
        }
    }

    private boolean verifySignature(PublicKey key, int algorithm, byte[] data, byte[] signature) {
        String signatureAlgorithm = switch (algorithm) {
            case -7 -> "SHA256withECDSA";
            case -257 -> "SHA256withRSA";
            case -8 -> "Ed25519";
            default -> throw new UnauthorizedException("Passkey sign-in failed");
        };
        try {
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** The WebAuthn user handle: the account UUID's string bytes, base64url. */
    private String userHandleFor(UUID userId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] decodeUrl(String value, String field) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid " + field + " encoding");
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private WebAuthnCredentialResponse toResponse(WebAuthnCredential credential) {
        return new WebAuthnCredentialResponse(
                credential.getId(),
                credential.getLabel(),
                credential.getCreatedAt(),
                credential.getLastUsedAt()
        );
    }
}
