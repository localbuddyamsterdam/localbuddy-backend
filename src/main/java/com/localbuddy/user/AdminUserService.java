package com.localbuddy.user;

import com.localbuddy.auth.RefreshTokenService;
import com.localbuddy.auth.TemporaryPasswordGenerator;
import com.localbuddy.common.NameFormatter;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final RefreshTokenService refreshTokenService;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            TemporaryPasswordGenerator temporaryPasswordGenerator,
                            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Onboards a user with an admin-generated temporary password. The account is
     * ACTIVE immediately (the admin vouches for it) but flagged so the user must
     * choose their own password on first login. The temporary password is returned
     * once so the admin can relay it.
     */
    @Transactional
    public AdminUserCreatedResponse createUser(CreateUserRequest request) {
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
        return new AdminUserCreatedResponse(
                saved.getId(), saved.getFirstName(), saved.getLastName(), saved.getPreferredName(),
                saved.getEmail(), saved.getRole(), saved.getStatus(), temporaryPassword);
    }

    /**
     * Resets a user's password to a fresh admin-generated temporary one and forces
     * a change on next login. Works for any role (including other admins) — used as
     * a fallback when the self-service reset email isn't an option. All the user's
     * existing sessions are revoked so the temporary password is the only way back in.
     */
    @Transactional
    public AdminTempPasswordResponse resetPassword(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Deleted users cannot be modified");
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(userId);

        return new AdminTempPasswordResponse(user.getId(), user.getEmail(), temporaryPassword);
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

        if (user.getRole() == UserRole.ADMIN) {
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