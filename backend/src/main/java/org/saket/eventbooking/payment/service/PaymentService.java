package org.saket.eventbooking.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.payment.config.PaymentProperties;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.dto.PaymentResponse;
import org.saket.eventbooking.payment.gateway.ChargeRequest;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.gateway.PaymentGateway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates "pay for this booking". Deliberately <b>not</b> one transaction: the gateway call is a
 * network round trip, and holding the booking row lock across it would block expiry and other
 * requests for that long. So: open the attempt (short transaction) → charge (no transaction, no
 * locks) → apply the outcome (short transaction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Client-generated, one per checkout screen. UUIDs fit; so does anything URL-safe of sane length. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    public record PayResult(PaymentResponse payment, boolean created) {}

    private final PaymentAttemptService attemptService;
    private final PaymentQueryService queryService;
    private final PaymentGateway gateway;
    private final PaymentProperties properties;

    /**
     * Insert-or-fetch on the idempotency key: a repeated request (double click, network retry, two
     * tabs) returns the payment created by the first one instead of charging again.
     */
    public PayResult pay(UUID userId, UUID bookingId, String idempotencyKey, PaymentRequest request) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Idempotency-Key must be 8-100 characters of A-Z, a-z, 0-9, '-' or '_'");
        }
        Optional<PaymentResponse> existing = queryService.findByIdempotencyKey(userId, bookingId, idempotencyKey);
        if (existing.isPresent()) {
            return new PayResult(existing.get(), false);
        }

        PaymentAttemptService.OpenedAttempt attempt;
        try {
            attempt = attemptService.open(userId, bookingId, idempotencyKey);
        } catch (PaymentAttemptService.IdempotencyKeyTakenException | DataIntegrityViolationException e) {
            // Lost the race to a concurrent request with the same key: that one owns the attempt.
            // (Usually detected under the booking lock; the unique constraint is the backstop.)
            return queryService.findByIdempotencyKey(userId, bookingId, idempotencyKey)
                    .map(payment -> new PayResult(payment, false))
                    .orElseThrow(() -> e);
        }

        ChargeResult result;
        try {
            result = gateway.charge(new ChargeRequest(attempt.paymentId(), attempt.amount(), properties.currency(),
                    request.paymentMethodToken(), idempotencyKey));
        } catch (RuntimeException e) {
            // Unknown outcome (timeout etc.): leave the payment PENDING. A webhook settles it, or the
            // hold expires and the booking FAILS (timeout after an attempt).
            log.warn("Gateway call failed for payment {}; outcome will come from webhook or hold expiry",
                    attempt.paymentId(), e);
            return new PayResult(queryService.get(attempt.paymentId()), true);
        }

        attemptService.applyOutcome(attempt.paymentId(), result.transactionId(), result.status(),
                result.failureReason(), null);
        return new PayResult(queryService.get(attempt.paymentId()), true);
    }
}
