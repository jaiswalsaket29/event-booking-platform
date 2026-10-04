package org.saket.eventbooking.payment.repository;

import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByTransactionId(String transactionId);

    long countByBookingId(UUID bookingId);

    boolean existsByBookingIdAndStatus(UUID bookingId, PaymentStatus status);

    /** Just the owning booking's id, without loading the payment into the persistence context. */
    @Query("select p.booking.id from Payment p where p.id = :id")
    Optional<UUID> findBookingIdById(@Param("id") UUID id);

    @Query("select p.id from Payment p where p.transactionId = :transactionId")
    Optional<UUID> findIdByTransactionId(@Param("transactionId") String transactionId);

    List<Payment> findByBookingIdIn(Collection<UUID> bookingIds);
}
