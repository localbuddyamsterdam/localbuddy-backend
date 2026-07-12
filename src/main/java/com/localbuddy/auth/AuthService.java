package com.localbuddy.auth;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserService;
import com.localbuddy.user.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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

        return new AuthResponse(
                savedUser.getId(),
                savedUser.getFullName(),
                savedUser.getEmail(),
                savedUser.getRole(),
                savedUser.getStatus(),
                "Signup successful"
        );
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadRequestException("Invalid email or password"));

        if (user.getPasswordHash() == null ||
                !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Invalid email or password");
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            throw new BadRequestException("Account is not active");
        }

        String accessToken = jwtService.generateAccessToken(user);

        return new LoginResponse(
                accessToken,
                "Bearer",
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus()
        );
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
}