package com.localbuddy.auth;

import com.localbuddy.auth.webauthn.WebAuthnCredentialRepository;
import com.localbuddy.common.NameFormatter;
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
import org.springframework.context.ApplicationEventPublisher;
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
    private final WebAuthnCredentialRepository webAuthnCredentialRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final String frontendBaseUrl;
    private final long resetTokenExpirationMinutes;
    private final long verificationTokenExpirationHours;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       AuthTokenService authTokenService,
                       WebAuthnCredentialRepository webAuthnCredentialRepository,
                       NotificationService notificationService,
                       ApplicationEventPublisher eventPublisher,
                       @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
                       @Value("${app.security.reset-token-expiration-minutes:30}") long resetTokenExpirationMinutes,
                       @Value("${app.security.verification-token-expiration-hours:48}") long verificationTokenExpirationHours) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.authTokenService = authTokenService;
        this.webAuthnCredentialRepository = webAuthnCredentialRepository;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
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

        if (request.role() == UserRole.ADMIN || request.role() == UserRole.SUPPORT
                || request.role() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException("Public signup is allowed only for LOGGED_IN_USER or LOCAL");
        }

        User user = new User();
        user.setFirstName(NameFormatter.requiredName(request.firstName(), "First name", NameFormatter.FIRST_NAME_MIN));
        user.setLastName(NameFormatter.requiredName(request.lastName(), "Last name", NameFormatter.LAST_NAME_MIN));
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
                savedUser.getFirstName(),
                savedUser.getLastName(),
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

        // A verified traveller signing in is a chance to pick up guest bookings they made with this
        // email (e.g. before this account existed, or while logged out) — see the helper for the gate.
        publishEmailConfirmedIfTraveller(user);

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

    /**
     * Mints a full session (access + refresh token) for an already-verified user — the shared
     * tail of every login path. Used by passkey sign-in, which proves identity cryptographically
     * instead of with a password.
     */
    @Transactional
    public LoginResponse issueSessionFor(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);
        publishEmailConfirmedIfTraveller(user);
        return buildLoginResponse(user, accessToken, refreshToken);
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        return new CurrentUserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPreferredName(),
                user.getEmail(),
                user.getPhone(),
                user.getAvatarUrl(),
                UserService.languagesToList(user.getLanguages()),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.isMustChangePassword()
        );
    }

    /**
     * Forced first-login password change: the authenticated user (who logged in
     * with a temporary password) sets their own. Clears the must-change flag,
     * revokes every existing session (including the temporary-password one), and
     * issues a fresh session so the caller continues seamlessly on a clean token.
     */
    @Transactional
    public LoginResponse setInitialPassword(UUID userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        if (!user.isMustChangePassword()) {
            throw new BadRequestException("No password change is required for this account");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Kill the temporary-password session everywhere, then mint a clean one.
        refreshTokenService.revokeAllForUser(user.getId());
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return buildLoginResponse(user, accessToken, refreshToken);
    }

    // ------------------------------------------------------------------ email-first sign-in

    /**
     * Probes whether an email already has an account, powering the email-first
     * sign-in flow: a returning user gets a password step, a first-time user gets
     * a sign-up step. {@code hasPassword} is false for social-only accounts so the
     * client can point them back to their provider. A soft-deleted account is
     * treated as unregistered so the email can be signed up afresh.
     */
    @Transactional(readOnly = true)
    public CheckEmailResponse checkEmail(String email) {
        String normalizedEmail = normalizeEmail(email);
        return userRepository.findByEmail(normalizedEmail)
                .filter(user -> user.getStatus() != UserStatus.DELETED)
                .map(user -> new CheckEmailResponse(
                        true,
                        user.getPasswordHash() != null,
                        webAuthnCredentialRepository.existsByUserId(user.getId())))
                .orElseGet(() -> new CheckEmailResponse(false, false, false));
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
        // A completed reset also satisfies any outstanding admin-forced change.
        user.setMustChangePassword(false);
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

        // The traveller has now proven this email is theirs — attach any guest bookings for it.
        publishEmailConfirmedIfTraveller(user);
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
                user.getFirstName(),
                user.getLastName(),
                user.getPreferredName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isMustChangePassword(),
                false
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Publishes {@link UserEmailConfirmedEvent} for a verified traveller so their past guest bookings
     * (made with this email) are attached to the account after the transaction commits. Gated to
     * verified {@link UserRole#LOGGED_IN_USER} accounts: a host/admin "my bookings" view is scoped
     * differently, and requiring a verified email stops an unverified sign-up from claiming a
     * stranger's guest bookings.
     */
    private void publishEmailConfirmedIfTraveller(User user) {
        if (user.getRole() == UserRole.LOGGED_IN_USER && user.isEmailVerified()) {
            eventPublisher.publishEvent(new UserEmailConfirmedEvent(user.getId(), user.getEmail()));
        }
    }
}
