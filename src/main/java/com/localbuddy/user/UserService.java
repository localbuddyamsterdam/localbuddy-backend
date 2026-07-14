package com.localbuddy.user;

import com.localbuddy.common.NameFormatter;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.media.ImageUploadValidator;
import com.localbuddy.media.MediaStorageProvider;
import com.localbuddy.media.StoredObject;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final MediaStorageProvider storageProvider;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, MediaStorageProvider storageProvider, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.storageProvider = storageProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPreferredName(),
                user.getEmail(),
                user.getPhone(),
                user.getAvatarUrl(),
                languagesToList(user.getLanguages()),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.isMustChangePassword(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    /** CSV column ⇄ list boundary for the self-reported languages. */
    public static List<String> languagesToList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Transactional
    public UserResponse updateOwnProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setFirstName(NameFormatter.requiredName(request.firstName(), "First name", NameFormatter.FIRST_NAME_MIN));
        user.setLastName(NameFormatter.requiredName(request.lastName(), "Last name", NameFormatter.LAST_NAME_MIN));
        user.setPreferredName(NameFormatter.optionalName(request.preferredName()));

        String phone = request.phone();
        user.setPhone(phone == null || phone.trim().isEmpty() ? null : phone.trim());

        // Only touch languages when the field is present — a null list means
        // "leave unchanged" so older clients that don't send it can't wipe it.
        if (request.languages() != null) {
            String csv = request.languages().stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
            if (csv != null && csv.length() > 300) {
                throw new BadRequestException("Languages list is too long");
            }
            user.setLanguages(csv);
        }

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse uploadMyAvatar(UUID userId, byte[] data, String contentType, String filename) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        ImageUploadValidator.validate(data, contentType);

        String previousKey = user.getAvatarStorageKey();
        StoredObject stored = storageProvider.upload("avatars", data, contentType, filename);
        user.setAvatarUrl(stored.url());
        user.setAvatarStorageKey(stored.storageKey());
        User saved = userRepository.save(user);

        // Best-effort cleanup of the previously stored blob (if any).
        if (previousKey != null && !previousKey.isBlank()) {
            storageProvider.delete(previousKey);
        }
        return toResponse(saved);
    }

    @Transactional
    public UserResponse deleteMyAvatar(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String key = user.getAvatarStorageKey();
        user.setAvatarUrl(null);
        user.setAvatarStorageKey(null);
        User saved = userRepository.save(user);
        if (key != null && !key.isBlank()) {
            storageProvider.delete(key);
        }
        return toResponse(saved);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new BadRequestException("Password change not supported for this account");
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        if (request.currentPassword().equals(request.newPassword())) {
            throw new BadRequestException("New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // A user who successfully sets their own password no longer owes one.
        user.setMustChangePassword(false);
        userRepository.save(user);
    }
}