package com.localbuddy.payout;

import com.localbuddy.payment.Payment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One payment's host portion included in a payout (ensures it's disbursed once). */
@Entity
@Table(name = "payout_items")
@Getter
@Setter
@NoArgsConstructor
public class PayoutItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_id", nullable = false)
    private Payout payout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
