package org.saket.eventbooking.payment.repository;

import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByTransactionId(String transactionId);

    long countByBookingId(UUID bookingId);

    boolean existsByBookingIdAndStatus(UUID bookingId, PaymentStatus status);

    boolean existsByBookingIdAndStatusAndCreatedAtAfter(UUID bookingId, PaymentStatus status, Instant after);

    /** Just the owning booking's id, without loading the payment into the persistence context. */
    @Query("select p.booking.id from Payment p where p.id = :id")
    Optional<UUID> findBookingIdById(@Param("id") UUID id);

    @Query("select p.id from Payment p where p.transactionId = :transactionId")
    Optional<UUID> findIdByTransactionId(@Param("transactionId") String transactionId);

    List<Payment> findByBookingIdIn(Collection<UUID> bookingIds);

    /**
     * Successful charges whose booking isn't CONFIRMED: paid after the hold ended, or the show was
     * cancelled by the organiser. Each one is a refund owed.
     */
    @Query("""
            select count(p) from Payment p
            where p.status = org.saket.eventbooking.payment.enums.PaymentStatus.SUCCESS
              and p.booking.status <> org.saket.eventbooking.booking.enums.BookingStatus.CONFIRMED
            """)
    long countPaymentsNeedingRefund();

    /** Attempts that never got an outcome although their booking has already ended. */
    @Query("""
            select count(p) from Payment p
            where p.status = org.saket.eventbooking.payment.enums.PaymentStatus.PENDING
              and p.booking.status <> org.saket.eventbooking.booking.enums.BookingStatus.PENDING
            """)
    long countStuckPendingPayments();
}
