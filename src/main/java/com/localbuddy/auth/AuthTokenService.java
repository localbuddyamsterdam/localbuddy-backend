package com.localbuddy.auth;

import com.localbuddy.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues and consumes single-use, expiring {@link AuthToken}s for password reset and email
 * verification. Issuing a new token first invalidates any prior unused token of the same purpose
 * for that user, so only the most recent link ever works.
 */
@Service
public class AuthTokenService {

    private final AuthTokenRepository authTokenRepository;

    public AuthTokenService(AuthTokenRepository authTokenRepository) {
        this.authTokenRepository = authTokenRepository;
    }

    /** Invalidates any prior unused token of this purpose for the user, then creates and returns a new raw token. */
    @Transactional
    public String issue(UUID userId, AuthTokenPurpose purpose, Duration ttl) {
        List<AuthToken> priorUnused = authTokenRepository.findByUserIdAndPurposeAndUsedAtIsNull(userId, purpose);
        Instant now = Instant.now();
        for (AuthToken prior : priorUnused) {
            prior.setUsedAt(now);
        }
        authTokenRepository.saveAll(priorUnused);

        AuthToken token = new AuthToken();
        token.setUserId(userId);
        token.setToken(UUID.randomUUID().toString());
        token.setPurpose(purpose);
        token.setExpiresAt(now.plus(ttl));
        authTokenRepository.save(token);
        return token.getToken();
    }

    /**
     * Validates and consumes a token for the given purpose. Throws {@link BadRequestException} if
     * the token is missing, of the wrong purpose, already used, or expired. On success it stamps
     * {@code usedAt} and returns the token so the caller can read {@code userId}.
     */
    @Transactional
    public AuthToken consume(String rawToken, AuthTokenPurpose purpose) {
        AuthToken token = authTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new BadRequestException("This link is invalid or has expired"));

        if (token.getPurpose() != purpose
                || token.getUsedAt() != null
                || token.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("This link is invalid or has expired");
        }

        token.setUsedAt(Instant.now());
        authTokenRepository.save(token);
        return token;
    }
}
