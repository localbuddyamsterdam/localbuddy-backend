package com.localbuddy.noshow;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Marks confirmed bookings COMPLETED once the no-show reporting window (48h after the experience
 * start) has closed and no no-show report is still awaiting admin verification. Bookings with a
 * pending report are left for the admin to resolve.
 */
@Service
public class BookingAutoCompletionService {

    private final BookingRepository bookingRepository;
    private final NoShowReportRepository noShowReportRepository;
    private final long autoCompleteAfterHours;

    public BookingAutoCompletionService(
            BookingRepository bookingRepository,
            NoShowReportRepository noShowReportRepository,
            @Value("${app.booking.auto-complete-after-hours:48}") long autoCompleteAfterHours) {
        this.bookingRepository = bookingRepository;
        this.noShowReportRepository = noShowReportRepository;
        this.autoCompleteAfterHours = autoCompleteAfterHours;
    }

    @Scheduled(fixedDelayString = "${app.booking.auto-complete-processor-delay-ms:300000}")
    @Transactional
    public void autoCompletePastBookings() {
        Instant cutoff = Instant.now().minusSeconds(autoCompleteAfterHours * 3600);

        List<Booking> due = bookingRepository
                .findTop100ByStatusAndAvailabilitySlot_StartTimeBeforeOrderByAvailabilitySlot_StartTimeAsc(
                        BookingStatus.CONFIRMED, cutoff);

        for (Booking booking : due) {
            // Wait for the admin if a no-show report is still pending on this booking.
            if (noShowReportRepository.existsByBookingIdAndStatus(booking.getId(), NoShowReportStatus.REQUESTED)) {
                continue;
            }
            booking.setStatus(BookingStatus.COMPLETED);
            booking.setCompletedAt(Instant.now());
            bookingRepository.save(booking);
        }
    }
}
