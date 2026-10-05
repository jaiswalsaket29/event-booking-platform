package org.saket.eventbooking.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.service.BookingCheckoutService;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.payment.config.PaymentProperties;
import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.repository.PaymentRepository;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The transactional halves of a payment: opening an attempt, and applying an outcome.
 * Both lock the booking row first, so attempts for one booking, outcomes, and hold expiry are
 * serialized, and the lock order (booking, then payment/inventory) is the same everywhere.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAttemptService {

    public record OpenedAttempt(UUID paymentId, BigDecimal amount) {}

    /** Another request with the same idempotency key got here first; the caller should replay it. */
    public static class IdempotencyKeyTakenException extends RuntimeException {
        IdempotencyKeyTakenException() {
            super("Idempotency key already used", null, false, false);
        }
    }

    public enum Applied {
        /** The outcome changed the payment (and possibly the booking). */
        APPLIED,
        /** Only bookkeeping (e.g. transaction id) was recorded; the outcome is still to come. */
        RECORDED,
        /** The payment was already terminal: a repeated webhook or sync result. No-op. */
        DUPLICATE,
        /** Money was taken but the booking won't be fulfilled (hold ended first, or the show was cancelled): refund owed. */
        LATE_PAYMENT
    }

    private final PaymentRepository paymentRepository;
    private final BookingCheckoutService checkoutService;
    private final PaymentProperties properties;

    /**
     * Inserts a PENDING payment for the caller's PENDING booking. A duplicate idempotency key fails the
     * flush with a unique-constraint violation, which the caller turns into "return the existing one".
     */
    @Transactional
    public OpenedAttempt open(UUID userId, UUID bookingId, String idempotencyKey) {
        Booking booking = checkoutService.lockForPayment(bookingId, userId);

        // Checked under the booking lock: a concurrent request with the same key may have committed its
        // attempt while we waited. That's a replay, not a second payment "in progress".
        if (paymentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            throw new IdempotencyKeyTakenException();
        }
        if (paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)) {
            throw new ConflictException("A payment for this booking is already in progress");
        }
        if (paymentRepository.countByBookingId(bookingId) >= properties.maxAttempts()) {
            throw new ConflictException("No payment attempts left for this booking");
        }

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setAmount(booking.getTotalAmount()); // always the server's amount, never the client's
        payment.setIdempotencyKey(idempotencyKey);
        payment.setCreatedAt(Instant.now());
        payment.transitionTo(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(payment);
        return new OpenedAttempt(payment.getId(), payment.getAmount());
    }

    /**
     * The one place a gateway outcome is applied, whether it came from the synchronous charge response
     * or from a webhook, in any order and any number of times.
     *
     * @param reportedAmount amount the provider says it charged (webhooks); null to skip the check
     */
    @Transactional
    public Applied applyOutcome(UUID paymentId, String transactionId, ChargeResult.Status status,
                                String failureReason, BigDecimal reportedAmount) {
        UUID bookingId = paymentRepository.findBookingIdById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        Booking booking = checkoutService.lock(bookingId).orElseThrow();
        // Loaded after the booking lock, so this is the committed state, not a stale copy.
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();

        if (transactionId != null) {
            if (payment.getTransactionId() == null) {
                payment.setTransactionId(transactionId);
            } else if (!payment.getTransactionId().equals(transactionId)) {
                throw new ConflictException("Transaction id doesn't match payment " + paymentId);
            }
        }
        if (payment.getStatus().isTerminal()) {
            return Applied.DUPLICATE;
        }
        if (status == ChargeResult.Status.PROCESSING) {
            return Applied.RECORDED;
        }

        if (status == ChargeResult.Status.SUCCEEDED) {
            if (reportedAmount != null && reportedAmount.compareTo(payment.getAmount()) != 0) {
                log.error("Payment {} reported {} but {} was due; treating as failed", paymentId, reportedAmount,
                        payment.getAmount());
                return fail(payment, booking, "Amount mismatch");
            }
            payment.transitionTo(PaymentStatus.SUCCESS);
            payment.setPaidAt(Instant.now());
            boolean onSale = booking.getSession().getStatus() == SessionStatus.SCHEDULED;
            if (booking.getStatus() == BookingStatus.PENDING && onSale) {
                checkoutService.confirm(booking);
                return Applied.APPLIED;
            }
            if (booking.getStatus() == BookingStatus.PENDING) {
                // Paid while the show was being cancelled: never confirm a ticket for a cancelled session.
                checkoutService.endUnpaid(booking, BookingStatus.CANCELLED);
            }
            // Refunds are out of scope; record the truth (money taken) and make it visible to admins.
            log.error("LATE PAYMENT: payment {} succeeded but booking {} is {}; needs a manual refund",
                    paymentId, bookingId, booking.getStatus());
            return Applied.LATE_PAYMENT;
        }
        return fail(payment, booking, failureReason != null ? failureReason : "Payment failed");
    }

    /**
     * Failed attempt. The booking stays PENDING (the user can retry with a new key) until the attempt
     * cap is reached; then it FAILS and its tickets are released in this same transaction.
     */
    private Applied fail(Payment payment, Booking booking, String reason) {
        payment.transitionTo(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        UUID bookingId = booking.getId();
        boolean attemptsExhausted = paymentRepository.countByBookingId(bookingId) >= properties.maxAttempts()
                && !paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING);
        if (booking.getStatus() == BookingStatus.PENDING && attemptsExhausted) {
            checkoutService.endUnpaid(booking, BookingStatus.FAILED);
        }
        return Applied.APPLIED;
    }
}
