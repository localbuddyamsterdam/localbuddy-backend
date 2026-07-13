package com.localbuddy.admin;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.gdpr.DataDeletionRequestRepository;
import com.localbuddy.gdpr.DataDeletionStatus;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.noshow.NoShowReportRepository;
import com.localbuddy.noshow.NoShowReportStatus;
import com.localbuddy.payment.Payment;
import com.localbuddy.payment.PaymentRepository;
import com.localbuddy.payment.PaymentStatus;
import com.localbuddy.payout.PayoutRepository;
import com.localbuddy.payout.PayoutStatus;
import com.localbuddy.review.ReviewRepository;
import com.localbuddy.review.ReviewStatus;
import com.localbuddy.safety.SafetyReportRepository;
import com.localbuddy.safety.SafetyReportStatus;
import com.localbuddy.tripsafety.TripSafetyEventRepository;
import com.localbuddy.tripsafety.TripSafetyEventType;
import com.localbuddy.trustsafety.TrustSafetyReportRepository;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

@Service
public class AdminDashboardService {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final int TOP_LIMIT = 5;

    private final UserRepository userRepository;
    private final LocalProfileRepository localProfileRepository;
    private final ExperienceRepository experienceRepository;
    private final BookingRepository bookingRepository;
    private final SafetyReportRepository safetyReportRepository;
    private final ReviewRepository reviewRepository;
    private final PaymentRepository paymentRepository;
    private final TripSafetyEventRepository tripSafetyEventRepository;
    private final NoShowReportRepository noShowReportRepository;
    private final DataDeletionRequestRepository dataDeletionRequestRepository;
    private final PayoutRepository payoutRepository;
    private final TrustSafetyReportRepository trustSafetyReportRepository;

    public AdminDashboardService(UserRepository userRepository,
                                 LocalProfileRepository localProfileRepository,
                                 ExperienceRepository experienceRepository,
                                 BookingRepository bookingRepository,
                                 SafetyReportRepository safetyReportRepository,
                                 ReviewRepository reviewRepository,
                                 PaymentRepository paymentRepository,
                                 TripSafetyEventRepository tripSafetyEventRepository,
                                 NoShowReportRepository noShowReportRepository,
                                 DataDeletionRequestRepository dataDeletionRequestRepository,
                                 PayoutRepository payoutRepository,
                                 TrustSafetyReportRepository trustSafetyReportRepository) {
        this.userRepository = userRepository;
        this.localProfileRepository = localProfileRepository;
        this.experienceRepository = experienceRepository;
        this.bookingRepository = bookingRepository;
        this.safetyReportRepository = safetyReportRepository;
        this.reviewRepository = reviewRepository;
        this.paymentRepository = paymentRepository;
        this.tripSafetyEventRepository = tripSafetyEventRepository;
        this.noShowReportRepository = noShowReportRepository;
        this.dataDeletionRequestRepository = dataDeletionRequestRepository;
        this.payoutRepository = payoutRepository;
        this.trustSafetyReportRepository = trustSafetyReportRepository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardSummaryResponse getSummary() {
        long cancelledBookings =
                bookingRepository.countByStatus(BookingStatus.CANCELLED_BY_LOGGED_IN_USER)
                        + bookingRepository.countByStatus(BookingStatus.CANCELLED_BY_LOCAL);

        return new AdminDashboardSummaryResponse(
                userRepository.count(),
                localProfileRepository.count(),
                localProfileRepository.countByApprovalStatus(LocalApprovalStatus.SUBMITTED),
                localProfileRepository.countByApprovalStatus(LocalApprovalStatus.APPROVED),
                experienceRepository.count(),
                experienceRepository.countByStatus(ExperienceStatus.SUBMITTED),
                experienceRepository.countByStatus(ExperienceStatus.APPROVED),
                bookingRepository.count(),
                bookingRepository.countByStatus(BookingStatus.REQUESTED),
                bookingRepository.countByStatus(BookingStatus.ACCEPTED),
                cancelledBookings,
                safetyReportRepository.countByStatus(SafetyReportStatus.OPEN),
                safetyReportRepository.countByStatus(SafetyReportStatus.IN_REVIEW),
                reviewRepository.countByStatus(ReviewStatus.VISIBLE),
                reviewRepository.countByStatus(ReviewStatus.HIDDEN)
        );
    }

    /**
     * Operational metrics over a rolling window of {@code days} days (clamped to [1, 365] by the caller).
     * Money and the revenue series are aggregated in Java from PAID payments; queue counts each fail
     * independently to 0 so a single missing repository can't take down the whole endpoint.
     */
    @Transactional(readOnly = true)
    public AdminDashboardMetricsResponse metrics(int days) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofDays(days));
        Instant prevStart = now.minus(Duration.ofDays(2L * days));
        Instant prevEnd = windowStart.minusMillis(1);

        List<Payment> current =
                paymentRepository.findByPaymentStatusAndPaidAtBetween(PaymentStatus.PAID, windowStart, now);
        List<Payment> previous =
                paymentRepository.findByPaymentStatusAndPaidAtBetween(PaymentStatus.PAID, prevStart, prevEnd);

        AdminDashboardMetricsResponse.Money money = buildMoney(current, previous);
        List<AdminDashboardMetricsResponse.SeriesPoint> series = buildSeries(current, windowStart, now);
        AdminDashboardMetricsResponse.Queues queues = buildQueues();
        List<AdminDashboardMetricsResponse.TopExperience> topExperiences = buildTopExperiences(current);
        List<AdminDashboardMetricsResponse.TopHost> topHosts = buildTopHosts(current);

        return new AdminDashboardMetricsResponse(days, money, series, queues, topExperiences, topHosts);
    }

    private AdminDashboardMetricsResponse.Money buildMoney(List<Payment> current, List<Payment> previous) {
        BigDecimal gmv = sum(current, Payment::getAmount);
        BigDecimal platformRevenue = sum(current, Payment::getPlatformFeeAmount);
        BigDecimal prevGmv = sum(previous, Payment::getAmount);
        BigDecimal prevPlatformRevenue = sum(previous, Payment::getPlatformFeeAmount);

        BigDecimal takeRatePct = gmv.signum() > 0
                ? platformRevenue.multiply(BigDecimal.valueOf(100)).divide(gmv, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new AdminDashboardMetricsResponse.Money(
                gmv, platformRevenue, takeRatePct, current.size(),
                prevGmv, prevPlatformRevenue, previous.size()
        );
    }

    private List<AdminDashboardMetricsResponse.SeriesPoint> buildSeries(
            List<Payment> current, Instant windowStart, Instant now) {
        // Seed every calendar day (UTC) in the window with zero, then add revenue per day.
        Map<LocalDate, BigDecimal> perDay = new LinkedHashMap<>();
        LocalDate startDate = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate endDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            perDay.put(d, BigDecimal.ZERO);
        }

        for (Payment p : current) {
            if (p.getPaidAt() == null || p.getAmount() == null) {
                continue;
            }
            LocalDate day = p.getPaidAt().atZone(ZoneOffset.UTC).toLocalDate();
            perDay.merge(day, p.getAmount(), BigDecimal::add);
        }

        List<AdminDashboardMetricsResponse.SeriesPoint> series = new ArrayList<>(perDay.size());
        for (Map.Entry<LocalDate, BigDecimal> e : perDay.entrySet()) {
            series.add(new AdminDashboardMetricsResponse.SeriesPoint(e.getKey().format(DAY_LABEL), e.getValue()));
        }
        return series;
    }

    private AdminDashboardMetricsResponse.Queues buildQueues() {
        long sosOpen = safeCount(() ->
                tripSafetyEventRepository.countByEventTypeAndResolvedFalse(TripSafetyEventType.SOS));
        long safetyOpen = safeCount(() ->
                safetyReportRepository.countByStatus(SafetyReportStatus.OPEN)
                        + safetyReportRepository.countByStatus(SafetyReportStatus.IN_REVIEW));
        long trustSafetyActionRequired = safeCount(() ->
                trustSafetyReportRepository.countByStatus(
                        com.localbuddy.trustsafety.SafetyReportStatus.ACTION_REQUIRED));
        long noShowPending = safeCount(() ->
                noShowReportRepository.countByStatus(NoShowReportStatus.REQUESTED));
        long gdprPending = safeCount(() ->
                dataDeletionRequestRepository.countByStatus(DataDeletionStatus.REQUESTED));
        long hostAppsPending = safeCount(() ->
                localProfileRepository.countByApprovalStatus(LocalApprovalStatus.SUBMITTED));
        long experiencesPending = safeCount(() ->
                experienceRepository.countByStatus(ExperienceStatus.SUBMITTED));
        long payoutsPendingOrFailed = safeCount(() ->
                payoutRepository.countByStatusIn(List.of(PayoutStatus.PENDING, PayoutStatus.FAILED)));
        long paymentsFailedOrRefundPending = safeCount(() ->
                paymentRepository.countByPaymentStatusIn(
                        List.of(PaymentStatus.FAILED, PaymentStatus.REFUND_PENDING)));
        long reviewsHidden = safeCount(() ->
                reviewRepository.countByStatus(ReviewStatus.HIDDEN));

        return new AdminDashboardMetricsResponse.Queues(
                sosOpen, safetyOpen, trustSafetyActionRequired, noShowPending, gdprPending,
                hostAppsPending, experiencesPending, payoutsPendingOrFailed,
                paymentsFailedOrRefundPending, reviewsHidden
        );
    }

    private List<AdminDashboardMetricsResponse.TopExperience> buildTopExperiences(List<Payment> current) {
        try {
            Map<UUID, BigDecimal> revenueById = new LinkedHashMap<>();
            Map<UUID, String> titleById = new LinkedHashMap<>();
            for (Payment p : current) {
                Booking booking = p.getBooking();
                if (booking == null || p.getAmount() == null) {
                    continue;
                }
                Experience experience = booking.getExperience();
                if (experience == null || experience.getId() == null) {
                    continue;
                }
                revenueById.merge(experience.getId(), p.getAmount(), BigDecimal::add);
                titleById.putIfAbsent(experience.getId(), experience.getTitle());
            }
            return revenueById.entrySet().stream()
                    .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                    .limit(TOP_LIMIT)
                    .map(e -> new AdminDashboardMetricsResponse.TopExperience(
                            e.getKey().toString(), titleById.get(e.getKey()), e.getValue()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<AdminDashboardMetricsResponse.TopHost> buildTopHosts(List<Payment> current) {
        try {
            Map<UUID, BigDecimal> revenueById = new LinkedHashMap<>();
            Map<UUID, String> nameById = new LinkedHashMap<>();
            for (Payment p : current) {
                Booking booking = p.getBooking();
                if (booking == null || p.getAmount() == null) {
                    continue;
                }
                LocalProfile host = booking.getLocalProfile();
                if (host == null || host.getId() == null) {
                    continue;
                }
                revenueById.merge(host.getId(), p.getAmount(), BigDecimal::add);
                nameById.putIfAbsent(host.getId(), hostName(host));
            }
            return revenueById.entrySet().stream()
                    .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                    .limit(TOP_LIMIT)
                    .map(e -> new AdminDashboardMetricsResponse.TopHost(
                            e.getKey().toString(), nameById.get(e.getKey()), e.getValue()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static String hostName(LocalProfile host) {
        String displayName = host.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return host.getId().toString();
    }

    private static BigDecimal sum(List<Payment> payments,
                                  java.util.function.Function<Payment, BigDecimal> field) {
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : payments) {
            BigDecimal v = field.apply(p);
            if (v != null) {
                total = total.add(v);
            }
        }
        return total;
    }

    private static long safeCount(LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
