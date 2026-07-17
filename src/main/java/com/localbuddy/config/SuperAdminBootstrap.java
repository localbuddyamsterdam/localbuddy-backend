package com.localbuddy.config;

import com.localbuddy.auth.RefreshTokenService;
import com.localbuddy.auth.TemporaryPasswordGenerator;
import com.localbuddy.user.StaffRoleAudit;
import com.localbuddy.user.StaffRoleAuditRepository;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * The operator-level root of trust for the admin team — runs in EVERY profile.
 *
 * <p>Reads {@code app.security.super-admins} (env {@code SUPER_ADMIN_EMAILS}, comma-
 * separated) and guarantees each listed email holds an ACTIVE SUPER_ADMIN account:
 * <ul>
 *   <li>missing account → created with a random temporary password that is logged
 *       ONCE and must be replaced on first login ({@code mustChangePassword});
 *       after that first login the password exists only as a bcrypt hash that
 *       nobody — admin, DBA or developer — can read back;</li>
 *   <li>existing account → promoted to SUPER_ADMIN and re-activated if needed;
 *       its password is never touched.</li>
 * </ul>
 *
 * <p>This is the ultimate failsafe: super admins can never delete themselves, and
 * the last one can never be demoted, but if the team is ever lost anyway (all
 * three lock themselves out, a bad data fix, an offboarding gone wrong) whoever
 * controls the infrastructure sets the env var and restarts the app — control of
 * the deployment, not any in-app account, is the real root of trust.
 *
 * <p>Break-glass recovery for a single lost credential: set
 * {@code app.security.super-admin-reset} (env {@code SUPER_ADMIN_RESET}) to one of
 * the configured emails and restart — that account gets a fresh temporary password
 * (logged once), all its sessions are revoked, and a forced password change is
 * armed. Clear the variable again afterwards. Every action here is written to the
 * {@code staff_role_audit} trail with actor = SYSTEM.
 *
 * <p>With the env var unset the runner is inert (it only warns if the platform has
 * no super admins at all), so nothing is seeded that the operator didn't ask for.
 */
@Component
public class SuperAdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final RefreshTokenService refreshTokenService;
    private final StaffRoleAuditRepository staffRoleAuditRepository;

    private final List<String> superAdminEmails;
    private final String breakGlassResetEmail;

    public SuperAdminBootstrap(UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               TemporaryPasswordGenerator temporaryPasswordGenerator,
                               RefreshTokenService refreshTokenService,
                               StaffRoleAuditRepository staffRoleAuditRepository,
                               @Value("${app.security.super-admins:}") String superAdminEmails,
                               @Value("${app.security.super-admin-reset:}") String breakGlassResetEmail) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.refreshTokenService = refreshTokenService;
        this.staffRoleAuditRepository = staffRoleAuditRepository;
        this.superAdminEmails = Arrays.stream(superAdminEmails.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(e -> !e.isEmpty())
                .distinct()
                .toList();
        this.breakGlassResetEmail = breakGlassResetEmail.trim().toLowerCase();
    }

    @Override
    @Transactional
    public void run(String... args) {
        for (String email : superAdminEmails) {
            userRepository.findByEmail(email).ifPresentOrElse(this::promoteIfNeeded, () -> create(email));
        }

        if (!breakGlassResetEmail.isEmpty()) {
            breakGlassReset(breakGlassResetEmail);
        }

        if (superAdminEmails.isEmpty()
                && userRepository.countByRoleAndStatus(UserRole.SUPER_ADMIN, UserStatus.ACTIVE) == 0) {
            log.warn("No active super admins exist and SUPER_ADMIN_EMAILS is not set — "
                    + "the admin team cannot be managed until one is configured.");
        }
    }

    /** Existing account listed as a super admin: ensure role + status; never touch the password. */
    private void promoteIfNeeded(User user) {
        boolean changed = false;

        if (user.getRole() != UserRole.SUPER_ADMIN) {
            log.warn("SuperAdminBootstrap: promoting {} from {} to SUPER_ADMIN", user.getEmail(), user.getRole());
            staffRoleAuditRepository.save(new StaffRoleAudit(
                    null, user.getId(), StaffRoleAudit.BOOTSTRAP_PROMOTED,
                    user.getEmail() + ": " + user.getRole() + " -> SUPER_ADMIN"));
            user.setRole(UserRole.SUPER_ADMIN);
            changed = true;
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("SuperAdminBootstrap: re-activating super admin {}", user.getEmail());
            user.setStatus(UserStatus.ACTIVE);
            changed = true;
        }

        if (changed) {
            userRepository.save(user);
        }
    }

    /** No account for a configured email: create it with a one-time-logged temporary password. */
    private void create(String email) {
        String temporaryPassword = temporaryPasswordGenerator.generate();

        User user = new User();
        user.setFirstName(firstNameFrom(email));
        user.setLastName("Admin");
        user.setEmail(email);
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        // The operator configured this address; trust it so self-service password
        // reset (the only way anyone but the holder can recover the account) works.
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);

        User saved = userRepository.save(user);
        staffRoleAuditRepository.save(new StaffRoleAudit(
                null, saved.getId(), StaffRoleAudit.BOOTSTRAP_CREATED,
                email + " created as SUPER_ADMIN"));

        logOneTimePassword("created super admin", email, temporaryPassword);
    }

    /** Operator-triggered recovery: fresh temp password, sessions revoked, change forced. */
    private void breakGlassReset(String email) {
        userRepository.findByEmail(email).ifPresentOrElse(user -> {
            if (user.getRole() != UserRole.SUPER_ADMIN) {
                log.warn("SUPER_ADMIN_RESET ignored: {} is not a super admin", email);
                return;
            }
            String temporaryPassword = temporaryPasswordGenerator.generate();
            user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
            user.setMustChangePassword(true);
            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
            refreshTokenService.revokeAllForUser(user.getId());
            staffRoleAuditRepository.save(new StaffRoleAudit(
                    null, user.getId(), StaffRoleAudit.BREAK_GLASS_RESET,
                    "break-glass password reset for " + email));
            logOneTimePassword("BREAK-GLASS reset", email, temporaryPassword);
        }, () -> log.warn("SUPER_ADMIN_RESET ignored: no account for {}", email));
    }

    /**
     * The only moment a credential is ever visible: a just-generated temporary
     * password, printed once to the log for the operator to relay. It stops working
     * the moment the holder signs in and sets their own (forced on first login).
     */
    private void logOneTimePassword(String what, String email, String temporaryPassword) {
        log.warn("""

                ==================== SUPER ADMIN BOOTSTRAP ====================
                {}: {}
                one-time temporary password: {}
                The holder must sign in and set their own password now — after
                that no one (including operators) can see or set it again.
                Remember to clear SUPER_ADMIN_RESET if you set it.
                ===============================================================""",
                what, email, temporaryPassword);
    }

    /** "sara.jansen@x.nl" → "Sara" (letters only; falls back to "Super"). */
    private static String firstNameFrom(String email) {
        String local = email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length());
        String letters = local.replaceAll("[^a-zA-Z].*$", "");
        if (letters.length() < 2) {
            return "Super";
        }
        return Character.toUpperCase(letters.charAt(0)) + letters.substring(1).toLowerCase();
    }
}
