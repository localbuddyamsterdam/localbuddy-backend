package com.localbuddy.user;

import com.localbuddy.auth.RefreshTokenService;
import com.localbuddy.auth.TemporaryPasswordGenerator;
import com.localbuddy.common.NameFormatter;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ForbiddenException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Admin/staff account management with a two-tier privilege model:
 *
 * <ul>
 *   <li><b>ADMIN</b> runs the platform (users, bookings, catalog, finance) but cannot
 *       touch the admin team itself.</li>
 *   <li><b>SUPER_ADMIN</b> additionally manages the team: creating staff accounts,
 *       promoting/demoting admins and super admins, and issuing temporary passwords
 *       to staff.</li>
 * </ul>
 *
 * Safety invariants enforced here (not in the UI):
 * <ul>
 *   <li>No one can change their own role — so the acting super admin always survives
 *       any mutation, which guarantees the platform can never end up with zero
 *       super admins.</li>
 *   <li>A super admin can only be demoted/removed by <i>another</i> super admin,
 *       making every removal attributable to a surviving actor (see
 *       {@link StaffRoleAudit}).</li>
 *   <li>Nobody — not even another super admin — can set or reset a super admin's
 *       password. Recovery is self-service (email reset) or the operator-level
 *       break-glass in {@code SuperAdminBootstrap}. Passwords exist only as bcrypt
 *       hashes, so they are invisible to admins, DBAs and developers alike.</li>
 * </ul>
 */
@Service
public class AdminUserService {

    /** Roles that make up the staff team, in ascending privilege order. */
    private static final Set<UserRole> STAFF_ROLES =
            Set.of(UserRole.SUPPORT, UserRole.ADMIN, UserRole.SUPER_ADMIN);

    /** Roles a super admin may assign via {@link #changeRole}. */
    private static final Set<UserRole> ASSIGNABLE_ROLES =
            Set.of(UserRole.LOGGED_IN_USER, UserRole.SUPPORT, UserRole.ADMIN, UserRole.SUPER_ADMIN);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final RefreshTokenService refreshTokenService;
    private final StaffRoleAuditRepository staffRoleAuditRepository;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            TemporaryPasswordGenerator temporaryPasswordGenerator,
                            RefreshTokenService refreshTokenService,
                            StaffRoleAuditRepository staffRoleAuditRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.refreshTokenService = refreshTokenService;
        this.staffRoleAuditRepository = staffRoleAuditRepository;
    }

    /**
     * Onboards a user with an admin-generated temporary password. The account is
     * ACTIVE immediately (the admin vouches for it) but flagged so the user must
     * choose their own password on first login. The temporary password is returned
     * once so the admin can relay it.
     *
     * <p>Creating staff accounts (SUPPORT/ADMIN/SUPER_ADMIN) is a super admin
     * privilege; plain admins may only onboard travellers and hosts.
     */
    @Transactional
    public AdminUserCreatedResponse createUser(UUID actorId, CreateUserRequest request) {
        User actor = requireActor(actorId);
        if (STAFF_ROLES.contains(request.role()) && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new ForbiddenException("Only a super admin can create admin or support accounts");
        }

        String normalizedEmail = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BadRequestException("Email already exists");
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();

        User user = new User();
        user.setFirstName(NameFormatter.requiredName(request.firstName(), "First name", NameFormatter.FIRST_NAME_MIN));
        user.setLastName(NameFormatter.requiredName(request.lastName(), "Last name", NameFormatter.LAST_NAME_MIN));
        user.setPreferredName(NameFormatter.optionalName(request.preferredName()));
        user.setEmail(normalizedEmail);
        user.setPhone(request.phone());
        user.setRole(request.role());
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);

        User saved = userRepository.save(user);
        if (STAFF_ROLES.contains(saved.getRole())) {
            staffRoleAuditRepository.save(new StaffRoleAudit(
                    actorId, saved.getId(), StaffRoleAudit.STAFF_CREATED,
                    saved.getEmail() + " created as " + saved.getRole()));
        }
        return new AdminUserCreatedResponse(
                saved.getId(), saved.getFirstName(), saved.getLastName(), saved.getPreferredName(),
                saved.getEmail(), saved.getRole(), saved.getStatus(), temporaryPassword);
    }

    /**
     * Resets a user's password to a fresh admin-generated temporary one and forces
     * a change on next login. All existing sessions are revoked so the temporary
     * password is the only way back in.
     *
     * <p>Tier rules: super admin passwords can never be reset here — only the
     * account holder can set one (self-service email reset, or the operator
     * break-glass). Admin/support passwords may only be reset by a super admin
     * (otherwise a compromised admin account could take over its peers). Regular
     * users may be reset by any admin, as before.
     */
    @Transactional
    public AdminTempPasswordResponse resetPassword(UUID actorId, UUID userId) {
        User actor = requireActor(actorId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new ForbiddenException(
                    "Super admin passwords can only be set by the account holder (use the self-service reset)");
        }
        if (STAFF_ROLES.contains(user.getRole()) && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new ForbiddenException("Only a super admin can reset another staff member's password");
        }
        if (user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Deleted users cannot be modified");
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(userId);

        if (STAFF_ROLES.contains(user.getRole())) {
            staffRoleAuditRepository.save(new StaffRoleAudit(
                    actorId, user.getId(), StaffRoleAudit.TEMP_PASSWORD_ISSUED,
                    "temporary password issued for " + user.getEmail()));
        }

        return new AdminTempPasswordResponse(user.getId(), user.getEmail(), temporaryPassword);
    }

    /**
     * Changes a user's role — the super admin team-management primitive. Promoting
     * a traveller to ADMIN/SUPER_ADMIN, demoting a super admin to ADMIN, and
     * "removing" a staff member (role back to LOGGED_IN_USER) all go through here.
     *
     * <p>Guards, in order:
     * <ul>
     *   <li>only super admins may call this;</li>
     *   <li>you can never change your own role (no self-promotion, no self-removal —
     *       the acting super admin always survives, so the team can't empty itself);</li>
     *   <li>hosts (LOCAL) are not managed here — their role carries listings/payouts;</li>
     *   <li>the last active super admin can never be demoted (the active super admin
     *       rows are locked for the transaction, so this holds even under concurrent
     *       demotions).</li>
     * </ul>
     */
    @Transactional
    public AdminUserResponse changeRole(UUID actorId, UUID userId, UserRole newRole) {
        User actor = requireActor(actorId);
        if (actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new ForbiddenException("Only a super admin can change roles");
        }
        if (actorId.equals(userId)) {
            throw new ForbiddenException("You cannot change your own role — ask another super admin");
        }
        if (newRole == null || !ASSIGNABLE_ROLES.contains(newRole)) {
            throw new BadRequestException("Role must be one of LOGGED_IN_USER, SUPPORT, ADMIN or SUPER_ADMIN");
        }

        // Serialize team mutations: lock the active super admin rows for this
        // transaction so two concurrent demotions can't both slip past the
        // "at least one super admin remains" check below.
        List<User> activeSuperAdmins =
                userRepository.lockAllByRoleAndStatus(UserRole.SUPER_ADMIN, UserStatus.ACTIVE);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Deleted users cannot be modified");
        }
        if (user.getRole() == UserRole.LOCAL) {
            throw new BadRequestException(
                    "Hosts cannot be given a staff role directly — create a separate staff account instead");
        }
        if (user.getRole() == newRole) {
            throw new BadRequestException("User already has role " + newRole);
        }

        UserRole oldRole = user.getRole();

        // Demoting a super admin must leave at least one other active super admin
        // (the self-change guard already means the actor survives; with the lock
        // above this holds even under concurrent demotions).
        if (oldRole == UserRole.SUPER_ADMIN && newRole != UserRole.SUPER_ADMIN
                && activeSuperAdmins.size() < 2) {
            throw new BadRequestException("At least one active super admin must remain");
        }

        user.setRole(newRole);
        userRepository.save(user);

        // Privilege reduction: kill refresh tokens so the demoted account can't mint
        // new sessions. (Access checks already read the DB role per request, so the
        // demotion itself is effective immediately.)
        if (STAFF_ROLES.contains(oldRole) && !STAFF_ROLES.contains(newRole)) {
            refreshTokenService.revokeAllForUser(userId);
        }

        String action = STAFF_ROLES.contains(oldRole) && !STAFF_ROLES.contains(newRole)
                ? StaffRoleAudit.STAFF_REMOVED
                : StaffRoleAudit.ROLE_CHANGED;
        staffRoleAuditRepository.save(new StaffRoleAudit(
                actorId, user.getId(), action, user.getEmail() + ": " + oldRole + " -> " + newRole));

        return toResponse(user);
    }

    /** The 50 most recent admin-team changes, with actor emails resolved (null actor = SYSTEM). */
    @Transactional(readOnly = true)
    public List<StaffRoleAuditResponse> getTeamAudit(UUID actorId) {
        User actor = requireActor(actorId);
        if (actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new ForbiddenException("Only a super admin can view the team audit");
        }
        List<StaffRoleAudit> entries = staffRoleAuditRepository.findTop50ByOrderByCreatedAtDesc();
        Set<UUID> actorIds = entries.stream()
                .map(StaffRoleAudit::getActorUserId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, String> emails = userRepository.findAllById(actorIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getEmail));
        return entries.stream()
                .map(e -> new StaffRoleAuditResponse(
                        e.getId(),
                        e.getActorUserId(),
                        e.getActorUserId() == null ? "system" : emails.getOrDefault(e.getActorUserId(), "unknown"),
                        e.getTargetUserId(),
                        e.getAction(),
                        e.getDetail(),
                        e.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> getUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse updateUserStatus(UUID userId, AdminUserStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.isAdminTier()) {
            throw new BadRequestException("Admin users cannot have their status changed from this API");
        }
        if (user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Deleted users cannot be modified");
        }

        UserStatus target = request.status();
        if (target != UserStatus.ACTIVE && target != UserStatus.SUSPENDED) {
            throw new BadRequestException("Status can only be set to ACTIVE or SUSPENDED");
        }

        user.setStatus(target);
        return toResponse(userRepository.save(user));
    }

    private User requireActor(UUID actorId) {
        return userRepository.findById(actorId)
                .orElseThrow(() -> new ForbiddenException("Acting user not found"));
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
