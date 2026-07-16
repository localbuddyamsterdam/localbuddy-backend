package com.localbuddy.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ServiceUnavailableException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies Google Sign-In ID tokens <em>locally</em> — validating the RS256
 * signature against Google's published public keys (JWKS) instead of calling
 * Google's {@code /tokeninfo} endpoint on every login. The JWKS is fetched once
 * and cached (honouring Google's {@code Cache-Control max-age}, ~hours), so a
 * normal login does no outbound network I/O at all — only the very first login
 * after a cache miss / key rotation fetches the certs.
 */
@Component
public class GoogleTokenVerifier implements SocialTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    private static final String CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> VALID_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");
    /** Fallback cache lifetime when Google doesn't send a usable Cache-Control header. */
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final String googleClientId;
    private final RestClient restClient = timeoutRestClient();

    /** kid → RSA public key. Replaced wholesale on refresh (so reads are lock-free). */
    private volatile Map<String, PublicKey> keyCache = Map.of();
    private volatile Instant keyCacheExpiry = Instant.EPOCH;

    /** Short connect/read timeouts so an unresponsive googleapis.com fails fast instead of hanging
     * the login request thread during a JWKS refresh. */
    private static RestClient timeoutRestClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    public GoogleTokenVerifier(@Value("${app.social.google.client-id:}") String googleClientId) {
        this.googleClientId = googleClientId;
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public VerifiedSocialUser verify(String idToken) {
        // Warm/refresh the JWKS before parsing so an unreachable Google surfaces as a
        // clean 503 here (rather than being wrapped by the JWT parser as "invalid token").
        currentKeys();

        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .keyLocator(new GoogleKeyLocator())
                    .clockSkewSeconds(60) // tolerate minor clock drift
                    .build()
                    .parseSignedClaims(idToken);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BadRequestException("Invalid Google token");
        }

        Claims claims = jws.getPayload();

        if (!VALID_ISSUERS.contains(claims.getIssuer())) {
            throw new BadRequestException("Invalid Google token");
        }

        // When a client id is configured, the token must have been issued for this app.
        if (googleClientId != null && !googleClientId.isBlank()) {
            Set<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(googleClientId)) {
                throw new BadRequestException("Google token was not issued for this application");
            }
        }

        if (claims.getSubject() == null) {
            throw new BadRequestException("Invalid Google token");
        }

        Object emailVerified = claims.get("email_verified");
        if (!"true".equalsIgnoreCase(String.valueOf(emailVerified))) {
            throw new BadRequestException("Google email is not verified");
        }

        return new VerifiedSocialUser(
                SocialProvider.GOOGLE,
                claims.getSubject(),
                claims.get("email", String.class),
                claims.get("name", String.class)
        );
    }

    /** Resolves the RSA verification key for a token by its {@code kid} header. */
    private final class GoogleKeyLocator implements Locator<Key> {
        @Override
        public Key locate(Header header) {
            Object kidValue = header.get("kid");
            if (kidValue == null) {
                return null;
            }
            String kid = kidValue.toString();

            PublicKey key = keyCache.get(kid);
            if (key == null) {
                // Unknown kid — Google may have rotated keys mid-request. Try one refresh,
                // but never throw through the JWT parser: a null key just fails verification
                // (a "please retry" for the rare rotation race), it isn't a 503.
                try {
                    refreshKeys();
                } catch (RuntimeException ignored) {
                    // keep whatever we had
                }
                key = keyCache.get(kid);
            }
            return key;
        }
    }

    /** Cached keys, refreshing first if the cache has expired. */
    private Map<String, PublicKey> currentKeys() {
        if (Instant.now().isAfter(keyCacheExpiry)) {
            try {
                refreshKeys();
            } catch (RuntimeException ex) {
                if (keyCache.isEmpty()) {
                    throw ex; // no keys at all — can't verify
                }
                // Otherwise serve the (stale) cache rather than failing logins on a transient error.
                log.warn("Google JWKS refresh failed; using cached keys", ex);
            }
        }
        return keyCache;
    }

    private synchronized void refreshKeys() {
        // Another thread may have refreshed while we waited on the lock.
        if (Instant.now().isBefore(keyCacheExpiry) && !keyCache.isEmpty()) {
            return;
        }

        ResponseEntity<GoogleCerts> response;
        try {
            response = restClient.get().uri(CERTS_URL).retrieve().toEntity(GoogleCerts.class);
        } catch (ResourceAccessException ex) {
            throw new ServiceUnavailableException("Google sign-in is temporarily unavailable. Please try again.");
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Google sign-in is temporarily unavailable. Please try again.");
        }

        GoogleCerts certs = response.getBody();
        if (certs == null || certs.keys() == null || certs.keys().isEmpty()) {
            throw new ServiceUnavailableException("Google sign-in is temporarily unavailable. Please try again.");
        }

        Map<String, PublicKey> next = new HashMap<>();
        for (GoogleKey k : certs.keys()) {
            if (!"RSA".equalsIgnoreCase(k.kty()) || k.kid() == null || k.n() == null || k.e() == null) {
                continue;
            }
            try {
                next.put(k.kid(), toRsaPublicKey(k.n(), k.e()));
            } catch (Exception ex) {
                log.warn("Skipping malformed Google JWK kid={}", k.kid(), ex);
            }
        }
        if (next.isEmpty()) {
            throw new ServiceUnavailableException("Google sign-in is temporarily unavailable. Please try again.");
        }

        keyCache = Map.copyOf(next);
        keyCacheExpiry = Instant.now().plus(ttlFrom(response.getHeaders()));
    }

    private static PublicKey toRsaPublicKey(String modulusB64Url, String exponentB64Url) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(modulusB64Url));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(exponentB64Url));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    /** Prefer Google's Cache-Control max-age; fall back to a sane default. */
    private static Duration ttlFrom(HttpHeaders headers) {
        long maxAge = headers.getCacheControl() == null ? -1 : parseMaxAge(headers.getCacheControl());
        if (maxAge > 0) {
            return Duration.ofSeconds(maxAge);
        }
        return DEFAULT_TTL;
    }

    private static long parseMaxAge(String cacheControl) {
        for (String part : cacheControl.split(",")) {
            String p = part.trim();
            if (p.startsWith("max-age=")) {
                try {
                    return Long.parseLong(p.substring("max-age=".length()).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleCerts(List<GoogleKey> keys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleKey(String kid, String kty, String n, String e) {
    }
}
