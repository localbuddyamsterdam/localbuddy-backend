package com.localbuddy.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    List<Invoice> findByLocalProfileIdOrderByIssuedAtDesc(UUID localProfileId);

    List<Invoice> findByBookingIdOrderByIssuedAtDesc(UUID bookingId);

    List<Invoice> findByPayoutIdOrderByIssuedAtDesc(UUID payoutId);

    List<Invoice> findByBookingIdInOrderByIssuedAtDesc(java.util.Collection<UUID> bookingIds);

    List<Invoice> findAllByOrderByIssuedAtDesc();

    boolean existsByBookingIdAndInvoiceType(UUID bookingId, InvoiceType invoiceType);

    boolean existsByPayoutIdAndInvoiceType(UUID payoutId, InvoiceType invoiceType);

    @Query("SELECT i FROM Invoice i WHERE i.bookingId = :bookingId ORDER BY i.issuedAt DESC LIMIT 1")
    Optional<Invoice> findFirstByBookingIdOrderByIssuedAtDesc(@Param("bookingId") UUID bookingId);
}
