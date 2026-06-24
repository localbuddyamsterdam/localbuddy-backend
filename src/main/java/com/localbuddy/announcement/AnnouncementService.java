package com.localbuddy.announcement;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.notification.NotificationPreferenceService;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Host and platform announcements (one-to-many broadcasts) plus the "follow a host" subscription.
 * Delivery flows through the existing notification outbox: logged-in recipients get in-app always +
 * email when their email is enabled; guest recipients (booked without an account) get email.
 */
@Service
public class AnnouncementService {

    private static final Set<BookingStatus> GUEST_STATUSES =
            Set.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);

    private final AnnouncementRepository announcementRepository;
    private final HostFollowRepository hostFollowRepository;
    private final BookingRepository bookingRepository;
    private final LocalProfileRepository localProfileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;

    public AnnouncementService(AnnouncementRepository announcementRepository,
                               HostFollowRepository hostFollowRepository,
                               BookingRepository bookingRepository,
                               LocalProfileRepository localProfileRepository,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               NotificationPreferenceService preferenceService) {
        this.announcementRepository = announcementRepository;
        this.hostFollowRepository = hostFollowRepository;
        this.bookingRepository = bookingRepository;
        this.localProfileRepository = localProfileRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.preferenceService = preferenceService;
    }

    // ------------------------------------------------------------------ host

    @Transactional
    public AnnouncementResponse createHostAnnouncement(UUID hostUserId, CreateAnnouncementRequest request) {
        if (request.audience() == AnnouncementAudience.ALL_HOSTS) {
            throw new BadRequestException("ALL_HOSTS is reserved for platform announcements");
        }
        LocalProfile host = localProfileRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
        if (host.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new BadRequestException("Only approved hosts can post announcements");
        }

        Announcement announcement = new Announcement();
        announcement.setLocalProfile(host);
        announcement.setCreatedBy(host.getUser());
        announcement.setAudience(request.audience());
        announcement.setSubject(request.subject().trim());
        announcement.setBody(request.body().trim());
        announcement = announcementRepository.save(announcement);

        Map<UUID, User> users = new LinkedHashMap<>();
        Set<String> guestEmails = new LinkedHashSet<>();
        if (request.audience() == AnnouncementAudience.MY_FOLLOWERS || request.audience() == AnnouncementAudience.BOTH) {
            for (HostFollow f : hostFollowRepository.findByLocalProfileId(host.getId())) {
                users.putIfAbsent(f.getFollower().getId(), f.getFollower());
            }
        }
        if (request.audience() == AnnouncementAudience.MY_GUESTS || request.audience() == AnnouncementAudience.BOTH) {
            for (Booking b : bookingRepository.findByLocalProfileIdAndStatusIn(host.getId(), GUEST_STATUSES)) {
                addBookingRecipient(b, users, guestEmails);
            }
        }

        int count = deliver(announcement, NotificationType.HOST_ANNOUNCEMENT, users, guestEmails);
        announcement.setRecipientCount(count);
        return AnnouncementResponse.from(announcementRepository.save(announcement));
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listMyHostAnnouncements(UUID hostUserId) {
        LocalProfile host = localProfileRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
        return announcementRepository.findByLocalProfileIdOrderByCreatedAtDesc(host.getId()).stream()
                .map(AnnouncementResponse::from).toList();
    }

    // ------------------------------------------------------------------ admin / platform

    @Transactional
    public AnnouncementResponse createPlatformAnnouncement(UUID adminUserId, PlatformAnnouncementRequest request) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Announcement announcement = new Announcement();
        announcement.setLocalProfile(null);
        announcement.setCreatedBy(admin);
        announcement.setAudience(AnnouncementAudience.ALL_HOSTS);
        announcement.setSubject(request.subject().trim());
        announcement.setBody(request.body().trim());
        announcement = announcementRepository.save(announcement);

        Map<UUID, User> users = new LinkedHashMap<>();
        for (User u : userRepository.findByRole(UserRole.LOCAL)) {
            users.putIfAbsent(u.getId(), u);
        }
        int count = deliver(announcement, NotificationType.PLATFORM_ANNOUNCEMENT, users, Set.of());
        announcement.setRecipientCount(count);
        return AnnouncementResponse.from(announcementRepository.save(announcement));
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listAll() {
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(AnnouncementResponse::from).toList();
    }

    // ------------------------------------------------------------------ follow

    @Transactional
    public void followHost(UUID userId, UUID localProfileId) {
        if (hostFollowRepository.existsByFollowerIdAndLocalProfileId(userId, localProfileId)) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        LocalProfile host = localProfileRepository.findById(localProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found"));
        HostFollow follow = new HostFollow();
        follow.setFollower(user);
        follow.setLocalProfile(host);
        hostFollowRepository.save(follow);
    }

    @Transactional
    public void unfollowHost(UUID userId, UUID localProfileId) {
        hostFollowRepository.findByFollowerIdAndLocalProfileId(userId, localProfileId)
                .ifPresent(hostFollowRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<FollowedHostResponse> listMyFollows(UUID userId) {
        return hostFollowRepository.findByFollowerIdOrderByCreatedAtDesc(userId).stream()
                .map(FollowedHostResponse::from).toList();
    }

    // ------------------------------------------------------------------ helpers

    private void addBookingRecipient(Booking booking, Map<UUID, User> users, Set<String> guestEmails) {
        if (booking.getLoggedInUser() != null) {
            users.putIfAbsent(booking.getLoggedInUser().getId(), booking.getLoggedInUser());
        } else if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
            guestEmails.add(booking.getGuestEmail().trim().toLowerCase(Locale.ROOT));
        }
    }

    private int deliver(Announcement announcement, NotificationType type,
                        Map<UUID, User> users, Set<String> guestEmails) {
        String base = "announcement:" + announcement.getId();
        for (User user : users.values()) {
            notificationService.createInAppNotificationForUser(
                    user, type, announcement.getSubject(), announcement.getBody(),
                    "ANNOUNCEMENT", announcement.getId(), base + ":u:" + user.getId() + ":INAPP");
            if (preferenceService.isEmailEnabled(user.getId())) {
                notificationService.createEmailNotificationForUser(
                        user, type, announcement.getSubject(), announcement.getBody(),
                        "ANNOUNCEMENT", announcement.getId(), base + ":u:" + user.getId() + ":EMAIL");
            }
        }
        for (String email : guestEmails) {
            notificationService.createEmailNotificationForGuest(
                    email, null, type, announcement.getSubject(), announcement.getBody(),
                    "ANNOUNCEMENT", announcement.getId(), base + ":g:" + email + ":EMAIL");
        }
        return users.size() + guestEmails.size();
    }
}
