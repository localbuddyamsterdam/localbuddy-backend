package com.localbuddy.auth;

import com.localbuddy.common.NameFormatter;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SocialAuthService {

    private final Map<SocialProvider, SocialTokenVerifier> verifiers;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public SocialAuthService(List<SocialTokenVerifier> verifierList,
                             UserRepository userRepository,
                             JwtService jwtService,
                             RefreshTokenService refreshTokenService) {
        this.verifiers = verifierList.stream()
                .collect(Collectors.toMap(SocialTokenVerifier::provider, Function.identity()));
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public LoginResponse socialLogin(SocialLoginRequest request) {
        SocialTokenVerifier verifier = verifiers.get(request.provider());
        if (verifier == null) {
            throw new BadRequestException("Sign-in with " + request.provider() + " is not available yet");
        }

        VerifiedSocialUser verified = verifier.verify(request.token());

        String email = normalizeEmail(verified.email());
        if (email == null) {
            throw new BadRequestException("Social account did not provide an email address");
        }

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createSocialUser(verified, email));

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Account is not active");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return new LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPreferredName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isMustChangePassword()
        );
    }

    private User createSocialUser(VerifiedSocialUser verified, String email) {
        User user = new User();
        applyName(user, verified.name(), email);
        user.setEmail(email);
        user.setRole(UserRole.LOGGED_IN_USER);
        user.setStatus(UserStatus.ACTIVE);
        // The provider asserted this email, so treat it as verified. No password for social accounts.
        user.setEmailVerified(true);
        user.setPhoneVerified(false);
        return userRepository.save(user);
    }

    /**
     * Splits the provider's combined display name into first + last (title-cased). Falls back to
     * the email local-part when no name is supplied; a single-token name seeds both fields so the
     * required last name is always populated.
     */
    private static void applyName(User user, String rawName, String email) {
        String base = rawName != null && !rawName.isBlank()
                ? rawName.trim()
                : email.substring(0, Math.max(1, email.indexOf('@')));
        String[] split = NameFormatter.splitFullName(base);
        user.setFirstName(split[0]);
        user.setLastName(split[1]);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
