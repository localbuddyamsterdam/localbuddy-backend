package com.localbuddy.notification;

import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    public NotificationPreferenceService(NotificationPreferenceRepository preferenceRepository,
                                         UserRepository userRepository) {
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceResponse getMyPreferences(UUID userId) {
        return preferenceRepository.findByUserId(userId)
                .map(NotificationPreferenceResponse::from)
                .orElseGet(NotificationPreferenceResponse::defaults);
    }

    @Transactional
    public NotificationPreferenceResponse updateMyPreferences(UUID userId, UpdateNotificationPreferenceRequest request) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                    NotificationPreference created = new NotificationPreference();
                    created.setUser(user);
                    return created;
                });

        preference.setBookingReminders(request.bookingReminders());
        preference.setMarketingEmails(request.marketingEmails());
        preference.setEmailEnabled(request.emailEnabled());
        preference.setSmsEnabled(request.smsEnabled());

        return NotificationPreferenceResponse.from(preferenceRepository.save(preference));
    }

    /** Booking reminders default to ON when the user has no saved preference. */
    @Transactional(readOnly = true)
    public boolean isBookingRemindersEnabled(UUID userId) {
        return preferenceRepository.findByUserId(userId)
                .map(NotificationPreference::isBookingReminders)
                .orElse(true);
    }
}
