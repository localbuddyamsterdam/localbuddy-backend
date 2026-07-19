package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

/** Formats a booking's settled host payout (after commission) for host-facing messages. */
@Component
public class HostPayoutText {

    private final PaymentRepository paymentRepository;

    public HostPayoutText(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /** The host's actual payout for this booking, or a friendly fallback when no payment has settled yet. */
    public String forBooking(Booking booking) {
        return paymentRepository.findByBookingId(booking.getId())
                .map(Payment::getHostPayoutAmount)
                .filter(amount -> amount != null)
                .map(amount -> {
                    String currency = booking.getExperience() != null && booking.getExperience().getCurrency() != null
                            ? booking.getExperience().getCurrency() : "EUR";
                    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency;
                })
                .orElse("check your dashboard");
    }
}
