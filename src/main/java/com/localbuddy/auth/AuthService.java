package com.localbuddy.auth;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.UnauthorizedException;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserService;
import com.localbuddy.user.UserStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthTokenService authTokenService;
    private final NotificationService notificationService;
    private final String frontendBaseUrl;
    private final long resetTokenExpirationMinutes;
    private final long verificationTokenExpirationHours;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       AuthTokenService authTokenService,
                       NotificationService notificationService,
                       @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
                       @Value("${app.security.reset-token-expiration-minutes:30}") long resetTokenExpirationMinutes,
                       @Value("${app.security.verification-token-expiration-hours:48}") long verificationTokenExpirationHours) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.authTokenService = authTokenService;
        this.notificationService = notificationService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.resetTokenExpirationMinutes = resetTokenExpirationMinutes;
        this.verificationTokenExpirationHours = verificationTokenExpirationHours;
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BadRequestException("Email already exists");
        }

        if (request.role() == UserRole.ADMIN || request.role() == UserRole.SUPPORT) {
            throw new BadRequestException("Public signup is allowed only for LOGGED_IN_USER or LOCAL");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(normalizedEmail);
        user.setPhone(request.phone());
        user.setRole(request.role());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);

        User savedUser = userRepository.save(user);

        sendEmailVerification(savedUser);

        return new AuthResponse(
                savedUser.getId(),
                savedUser.getFullName(),
                savedUser.getEmail(),
                savedUser.getRole(),
                savedUser.getStatus(),
                "Signup successful"
        );
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (user.getPasswordHash() == null ||
                !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Account is not active");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return buildLoginResponse(user, accessToken, refreshToken);
    }

    /**
     * Rotates the supplied refresh token and issues a fresh access token. The old refresh token is
     * revoked and replaced; the caller receives both a new access token and a new refresh token.
     */
    @Transactional
    public LoginResponse refreshSession(String refreshToken) {
        RefreshRotation rotation = refreshTokenService.rotate(refreshToken);
        User user = rotation.user();
        String accessToken = jwtService.generateAccessToken(user);
        return buildLoginResponse(user, accessToken, rotation.newRefreshToken());
    }

    /** Revokes the supplied refresh token (idempotent). */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        return new CurrentUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getAvatarUrl(),
                UserService.languagesToList(user.getLanguages()),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isPhoneVerified()
        );
    }

    // ------------------------------------------------------------------ password reset

    /**
     * Starts a password reset. If a user with this email exists, issues a reset token and emails a
     * link. Silent on unknown emails — the controller always returns 204 so existence isn't revealed.
     */
    @Transactional
    public void forgotPassword(String email) {
        String normalizedEmail = normalizeEmail(email);
        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            String token = authTokenService.issue(
                    user.getId(), AuthTokenPurpose.PASSWORD_RESET,
                    Duration.ofMinutes(resetTokenExpirationMinutes));
            String link = frontendBaseUrl + "/auth/reset?token=" + token;
            String body = "We received a request to reset the password for your LocalBuddy account.\n\n"
                    + "Reset your password:\n" + link
                    + "\n\nThis link expires in " + resetTokenExpirationMinutes + " minutes."
                    + "\nIf you didn't request this, you can safely ignore this email — your password stays unchanged.";
            notificationService.createEmailNotificationForGuest(
                    user.getEmail(), null, NotificationType.PASSWORD_RESET,
                    "Reset your LocalBuddy password", body,
                    "USER", user.getId(), "password-reset:" + token);
        });
    }

    /** Consumes a reset token, sets the new password, and revokes all existing sessions. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        AuthToken authToken = authTokenService.consume(token, AuthTokenPurpose.PASSWORD_RESET);
        User user = userRepository.findById(authToken.getUserId())
                .orElseThrow(() -> new BadRequestException("This link is invalid or has expired"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(user.getId());
    }

    // ------------------------------------------------------------------ email verification

    /** Issues an email-verification token and emails a confirmation link. */
    @Transactional
    public void sendEmailVerification(User user) {
        String token = authTokenService.issue(
                user.getId(), AuthTokenPurpose.EMAIL_VERIFICATION,
                Duration.ofHours(verificationTokenExpirationHours));
        String link = frontendBaseUrl + "/auth/verify?token=" + token;
        String body = "Welcome to LocalBuddy! Please confirm your email address to activate your account.\n\n"
                + "Verify your email:\n" + link
                + "\n\nThis link expires in " + verificationTokenExpirationHours + " hours."
                + "\nIf you didn't create a LocalBuddy account, you can safely ignore this email.";
        notificationService.createEmailNotificationForGuest(
                user.getEmail(), null, NotificationType.EMAIL_VERIFICATION,
                "Verify your LocalBuddy email", body,
                "USER", user.getId(), "email-verify:" + token);
    }

    /** Consumes a verification token and marks the user's email verified (activating a pending account). */
    @Transactional
    public void verifyEmail(String token) {
        AuthToken authToken = authTokenService.consume(token, AuthTokenPurpose.EMAIL_VERIFICATION);
        User user = userRepository.findById(authToken.getUserId())
                .orElseThrow(() -> new BadRequestException("This link is invalid or has expired"));

        user.setEmailVerified(true);
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);
    }

    /** Re-sends a verification email if the account exists and isn't already verified. Silent otherwise. */
    @Transactional
    public void resendVerification(String email) {
        String normalizedEmail = normalizeEmail(email);
        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                sendEmailVerification(user);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private LoginResponse buildLoginResponse(User user, String accessToken, String refreshToken) {
        return new LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus()
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
