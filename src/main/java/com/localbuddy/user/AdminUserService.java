package com.localbuddy.user;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdminUserService {

    private final UserRepository userRepository;

    public AdminUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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