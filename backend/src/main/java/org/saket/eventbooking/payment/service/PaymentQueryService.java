package org.saket.eventbooking.payment.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.payment.dto.PaymentResponse;
import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;
import org.saket.eventbooking.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only payment facts for other domains (booking expiry, admin views). Depends only on the
 * payment repository, so the booking domain can use it without a dependency cycle.
 */
@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;

    /** Retry count is derived from the rows themselves (no counter column to drift). */
    @Transactional(readOnly = true)
    public long countAttempts(UUID bookingId) {
        return paymentRepository.countByBookingId(bookingId);
    }

    /** Whether an attempt opened after {@code since} is still waiting for its outcome. */
    @Transactional(readOnly = true)
    public boolean hasPaymentInFlightSince(UUID bookingId, Instant since) {
        return paymentRepository.existsByBookingIdAndStatusAndCreatedAtAfter(bookingId, PaymentStatus.PENDING, since);
    }

    /** The payment already created with this key, if any; 409 if the key belongs to another request. */
    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findByIdempotencyKey(UUID userId, UUID bookingId, String idempotencyKey) {
        Optional<Payment> payment = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (payment.isEmpty()) {
            return Optional.empty();
        }
        var booking = payment.get().getBooking();
        if (!booking.getId().equals(bookingId) || !booking.getUser().getId().equals(userId)) {
            throw new ConflictException("This Idempotency-Key was already used for a different request");
        }
        return Optional.of(PaymentResponse.from(payment.get()));
    }

    /** Money taken for bookings that won't be fulfilled (needs a manual refund; refunds are out of scope). */
    @Transactional(readOnly = true)
    public long countPaymentsNeedingRefund() {
        return paymentRepository.countPaymentsNeedingRefund();
    }

    /** PENDING attempts whose booking already ended: no outcome ever arrived; check with the provider. */
    @Transactional(readOnly = true)
    public long countStuckPendingPayments() {
        return paymentRepository.countStuckPendingPayments();
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        return PaymentResponse.from(paymentRepository.findById(paymentId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<PaymentResponse>> listForBookings(Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        return paymentRepository.findByBookingIdIn(bookingIds).stream()
                .sorted(Comparator.comparing(Payment::getCreatedAt))
                .map(PaymentResponse::from)
                .collect(Collectors.groupingBy(PaymentResponse::bookingId));
    }
}
