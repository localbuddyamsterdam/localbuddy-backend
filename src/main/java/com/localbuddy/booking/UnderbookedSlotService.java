package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guaranteed-departure support. The platform minimum is fixed (default 3). The
 * platform never cancels automatically — instead, when a slot is under the
 * minimum a fixed time before it starts, the host is notified and may choose to
 * cancel the slot (full refund to guests) up until the deadline.
 */
@Service
public class UnderbookedSlotService {

    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final BookingRepository bookingRepository;
    private final LocalProfileRepository localProfileRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;

    private final int minimumGuestsToConfirm;
    private final long noticeHours;
    private final long cancelDeadlineHours;

    public UnderbookedSlotService(
            AvailabilitySlotRepository availabilitySlotRepository,
            BookingRepository bookingRepository,
            LocalProfileRepository localProfileRepository,
            PaymentService paymentService,
            NotificationService notificationService,
            @Value("${app.booking.minimum-guests-to-confirm:3}") int minimumGuestsToConfirm,
            @Value("${app.booking.underbooked-notice-hours:36}") long noticeHours,
            @Value("${app.booking.underbooked-cancel-deadline-hours:24}") long cancelDeadlineHours
    ) {
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.bookingRepository = bookingRepository;
        this.localProfileRepository = localProfileRepository;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
        this.minimumGuestsToConfirm = minimumGuestsToConfirm;
        this.noticeHours = noticeHours;
        this.cancelDeadlineHours = cancelDeadlineHours;
    }

    /**
     * Notifies hosts whose slots have entered the notice window (between the
     * cancel deadline and the notice horizon before start) without reaching the
     * minimum. Sent once per slot via the notification dedupe key.
     */
    @Scheduled(fixedDelayString = "${app.booking.underbooked-processor-delay-ms:300000}")
    @Transactional
    public void notifyUnderbookedSlots() {
        Instant now = Instant.now();
        Instant windowStart = now.plusSeconds(cancelDeadlineHours * 3600);
        Instant windowEnd = now.plusSeconds(noticeHours * 3600);

        List<AvailabilitySlot> slots = availabilitySlotRepository.findUnderbookedSlotsForNotice(
                windowStart, windowEnd, AvailabilityStatus.AVAILABLE, minimumGuestsToConfirm);

        for (AvailabilitySlot slot : slots) {
            notifyHostOfUnderbookedSlot(slot);
        }
    }

    private void notifyHostOfUnderbookedSlot(AvailabilitySlot slot) {
        User host = slot.getLocalProfile() != null ? slot.getLocalProfile().getUser() : null;
        if (host == null) {
            return;
        }

        String title = slot.getExperience() != null ? slot.getExperience().getTitle() : "your experience";
        String subject = "Your experience slot is under-booked";
        String message = "Your slot for \"" + title + "\" starting " + slot.getStartTime()
                + " has " + slot.getBookedCount() + " of the minimum " + minimumGuestsToConfirm
                + " guests. You can cancel this slot (guests are refunded in full) up to "
                + cancelDeadlineHours + " hours before it starts, or let it go ahead.";

        notificationService.createEmailAndInAppNotificationForUser(
                host, NotificationType.SLOT_UNDERBOOKED_HOST_NOTICE, subject, message,
                "AVAILABILITY_SLOT", slot.getId(), "SLOT_UNDERBOOKED:" + slot.getId());
    }

    /**
     * Host-initiated cancellation of an under-booked slot. Allowed only while the
     * slot is below the minimum and the cancellation deadline has not passed.
     * Cancels every active booking with a full refund.
     */
    @Transactional
    public UnderbookedSlotCancellationResponse cancelUnderbookedSlot(UUID localUserId, UUID slotId, String reason) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        if (slot.getLocalProfile() == null ||
                !slot.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Availability slot not found");
        }

        if (slot.getStatus() == AvailabilityStatus.CANCELLED) {
            throw new BadRequestException("Slot is already cancelled");
        }

        Instant now = Instant.now();
        if (slot.getStartTime() == null || !slot.getStartTime().isAfter(now)) {
            throw new BadRequestException("Slot has already started");
        }

        if (slot.getCapacity() < minimumGuestsToConfirm) {
            throw new BadRequestException("This slot is not eligible for minimum-not-met cancellation");
        }

        if (slot.getBookedCount() >= minimumGuestsToConfirm) {
            throw new BadRequestException("This slot has met the minimum and cannot be cancelled as under-booked");
        }

        Instant deadline = slot.getStartTime().minusSeconds(cancelDeadlineHours * 3600);
        if (now.isAfter(deadline)) {
            throw new BadRequestException(
                    "The cancellation window has closed (within " + cancelDeadlineHours + " hours of start)");
        }

        List<Booking> activeBookings = bookingRepository.findByAvailabilitySlotIdAndStatusIn(
                slotId, activeBookingStatuses());

        for (Booking booking : activeBookings) {
            paymentService.fullyRefundBookingPayment(booking, reason);
            booking.setStatus(BookingStatus.CANCELLED_MINIMUM_NOT_MET);
            booking.setCancelledAt(now);
            booking.setCancellationReason(optionalTrim(reason));
            bookingRepository.save(booking);
            notifyCustomerOfCancellation(booking);
        }

        slot.setStatus(AvailabilityStatus.CANCELLED);
        slot.setBookedCount(0);
        availabilitySlotRepository.save(slot);

        return new UnderbookedSlotCancellationResponse(
                slot.getId(), AvailabilityStatus.CANCELLED, activeBookings.size());
    }

    private void notifyCustomerOfCancellation(Booking booking) {
        String subject = "Your booking was cancelled — full refund";
        String message = "We're sorry — the experience slot for booking " + booking.getBookingReference()
                + " was cancelled because it did not reach the minimum number of guests. "
                + "You have been refunded in full.";

        if (booking.getTravelerUser() != null) {
            notificationService.createEmailAndInAppNotificationForUser(
                    booking.getTravelerUser(), NotificationType.SLOT_CANCELLED_MINIMUM_NOT_MET,
                    subject, message, "BOOKING", booking.getId(),
                    "SLOT_CANCELLED_MIN:" + booking.getId());
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.SLOT_CANCELLED_MINIMUM_NOT_MET,
                    subject, message, "BOOKING", booking.getId(),
                    "SLOT_CANCELLED_MIN:" + booking.getId() + ":" + booking.getGuestEmail());
        }
    }

    private Set<BookingStatus> activeBookingStatuses() {
        return Set.of(
                BookingStatus.REQUESTED,
                BookingStatus.ACCEPTED,
                BookingStatus.PENDING_PAYMENT,
                BookingStatus.CONFIRMED
        );
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
