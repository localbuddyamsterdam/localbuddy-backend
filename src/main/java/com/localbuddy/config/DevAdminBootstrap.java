package com.localbuddy.config;

import com.localbuddy.common.NameFormatter;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DevAdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final String adminEmail;
    private final String adminPassword;
    private final String adminFullName;
    private final String adminPhone;

    public DevAdminBootstrap(UserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             @Value("${app.dev-admin.email:admin@test.com}") String adminEmail,
                             @Value("${app.dev-admin.password:Password@123}") String adminPassword,
                             @Value("${app.dev-admin.full-name:Test Admin}") String adminFullName,
                             @Value("${app.dev-admin.phone:+10000000000}") String adminPhone) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminFullName = adminFullName;
        this.adminPhone = adminPhone;
    }

    @Override
    @Transactional
    public void run(String... args) {
        userRepository.findByEmail(adminEmail.toLowerCase())
                .ifPresentOrElse(
                        existingAdmin -> {
                            boolean changed = false;

                            if (existingAdmin.getStatus() != UserStatus.ACTIVE) {
                                existingAdmin.setStatus(UserStatus.ACTIVE);
                                changed = true;
                            }

                            if (existingAdmin.getRole() != UserRole.ADMIN) {
                                existingAdmin.setRole(UserRole.ADMIN);
                                changed = true;
                            }

                            if (changed) {
                                userRepository.save(existingAdmin);
                            }
                        },
                        () -> {
                            User admin = new User();
                            String[] name = NameFormatter.splitFullName(adminFullName);
                            admin.setFirstName(name[0]);
                            admin.setLastName(name[1]);
                            admin.setEmail(adminEmail.toLowerCase());
                            admin.setPhone(adminPhone);
                            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
                            admin.setRole(UserRole.ADMIN);
                            admin.setStatus(UserStatus.ACTIVE);

                            userRepository.save(admin);
                        }
                );
    }
}