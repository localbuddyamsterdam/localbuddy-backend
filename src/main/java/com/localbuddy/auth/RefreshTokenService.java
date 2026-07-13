package com.localbuddy.auth;

import com.localbuddy.common.exception.UnauthorizedException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manages long-lived, rotating refresh tokens. Every successful rotation revokes the presented
 * token and issues a replacement (tracked via {@code replacedBy}). Presenting an already-revoked
 * token is treated as reuse (stolen token): the whole active chain for that user is revoked.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final long refreshDays;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               @Value("${app.security.jwt.refresh-token-expiration-days:30}") long refreshDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.refreshDays = refreshDays;
    }

    /** Creates and persists a new refresh token for the user; returns the raw token value. */
    @Transactional
    public String issue(User user) {
        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plus(Duration.ofDays(refreshDays)));
        token.setRevoked(false);
        refreshTokenRepository.save(token);
        return token.getToken();
    }

    /**
     * Rotates a refresh token: validates it, revokes it, issues a replacement, and returns the
     * owning user together with the new raw token. Reuse of an already-revoked token revokes the
     * user's entire active chain and fails.
     */
    @Transactional
    public RefreshRotation rotate(String rawToken) {
        RefreshToken existing = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired session"));

        // Reuse detection: a revoked token being presented again means it was replaced (or a
        // stolen copy is in play). Revoke everything still active for this user and reject.
        if (existing.isRevoked()) {
            revokeAllForUser(existing.getUserId());
            throw new UnauthorizedException("Invalid or expired session");
        }

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Invalid or expired session");
        }

        User user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired session"));

        String newRawToken = issue(user);
        existing.setRevoked(true);
        existing.setReplacedBy(newRawToken);
        refreshTokenRepository.save(existing);

        return new RefreshRotation(user, newRawToken);
    }

    /** Revokes a single refresh token. Idempotent — unknown or already-revoked tokens are no-ops. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByToken(rawToken).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            }
        });
    }

    /** Revokes every active refresh token for a user (e.g. after a password reset or reuse detection). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
        for (RefreshToken token : active) {
            token.setRevoked(true);
        }
        refreshTokenRepository.saveAll(active);
    }
}
