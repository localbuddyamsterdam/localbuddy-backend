package com.localbuddy.adminops;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ConflictException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.CityRepository;
import com.localbuddy.experience.ExperienceCategoryRepository;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.media.ExperiencePhotoRepository;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the strict-create semantics of {@link AdminOpsService#createOrApproveLocalProfile}:
 * an admin re-submitting the "create host" form for an email that already has a host profile
 * must get a 409 Conflict, not a silent overwrite. The old upsert wiped fields the host had
 * submitted themselves (bio, motivation, banking details) and returned 201, which made the
 * admin UI show the same host as freshly created every time.
 */
class AdminOpsServiceDuplicateHostTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LocalProfileRepository localProfileRepository = mock(LocalProfileRepository.class);

    private final AdminOpsService service = new AdminOpsService(
            userRepository,
            localProfileRepository,
            mock(ExperienceRepository.class),
            mock(ExperienceCategoryRepository.class),
            mock(CityRepository.class),
            mock(ExperiencePhotoRepository.class)
    );

    private static AdminCreateLocalProfileRequest request(String email) {
        return new AdminCreateLocalProfileRequest(
                email, "Anna Host",
                null, null, null, null, null, null,
                List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null
        );
    }

    private User localUser(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setRole(UserRole.LOCAL);
        return user;
    }

    @Test
    @DisplayName("existing host profile → 409 Conflict, nothing saved")
    void duplicateHost_isRejected() {
        User user = localUser("anna@example.com");
        when(userRepository.findByEmail("anna@example.com")).thenReturn(Optional.of(user));
        when(localProfileRepository.existsByUserId(user.getId())).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.createOrApproveLocalProfile(request("Anna@Example.com ")));

        assertTrue(ex.getMessage().contains("anna@example.com"));
        verify(localProfileRepository, never()).save(any(LocalProfile.class));
    }

    @Test
    @DisplayName("unknown email → 404")
    void unknownEmail_isNotFound() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createOrApproveLocalProfile(request("ghost@example.com")));
    }

    @Test
    @DisplayName("non-LOCAL user → 400")
    void nonLocalRole_isRejected() {
        User user = localUser("traveler@example.com");
        user.setRole(UserRole.LOGGED_IN_USER);
        when(userRepository.findByEmail("traveler@example.com")).thenReturn(Optional.of(user));

        assertThrows(BadRequestException.class,
                () -> service.createOrApproveLocalProfile(request("traveler@example.com")));

        verify(localProfileRepository, never()).save(any(LocalProfile.class));
    }
}
