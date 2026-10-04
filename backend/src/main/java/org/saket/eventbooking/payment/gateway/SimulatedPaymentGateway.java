package org.saket.eventbooking.payment.gateway;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic stand-in for a card processor. The outcome depends only on the payment-method token,
 * mirroring the "test card" conventions of real providers:
 * <ul>
 *   <li>{@code tok_success} → succeeds</li>
 *   <li>{@code tok_decline} → fails, "Card declined"</li>
 *   <li>{@code tok_insufficient_funds} → fails, "Insufficient funds"</li>
 *   <li>anything else → fails, "Unsupported test payment method"</li>
 * </ul>
 * Like a real provider it honours idempotency keys: retrying a charge with the same key returns the
 * original result instead of charging twice.
 * <p>
 * In {@code WEBHOOK} mode (the default) a charge answers PROCESSING and the outcome arrives later as a
 * signed webhook, so the app runs on "the webhook is the source of truth". {@code SYNC} answers
 * immediately (used by most tests).
 */
@Slf4j
public class SimulatedPaymentGateway implements PaymentGateway {

    public static final String TOKEN_SUCCESS = "tok_success";
    public static final String TOKEN_DECLINE = "tok_decline";
    public static final String TOKEN_INSUFFICIENT_FUNDS = "tok_insufficient_funds";

    private final Map<String, ChargeResult> chargesByIdempotencyKey = new ConcurrentHashMap<>();
    private final SimulatedWebhookSender webhookSender; // null = SYNC mode

    /** SYNC mode: outcomes are returned directly. */
    public SimulatedPaymentGateway() {
        this(null);
    }

    /** WEBHOOK mode when {@code webhookSender} is given. */
    public SimulatedPaymentGateway(SimulatedWebhookSender webhookSender) {
        this.webhookSender = webhookSender;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        return chargesByIdempotencyKey.computeIfAbsent(request.idempotencyKey(), key -> {
            ChargeResult outcome = decide(request.paymentMethodToken());
            log.info("Simulated charge {} for payment {}: {} {} -> {}{}", outcome.transactionId(), request.paymentId(),
                    request.amount(), request.currency(), outcome.status(), webhookSender != null ? " (via webhook)" : "");
            if (webhookSender == null) {
                return outcome;
            }
            webhookSender.deliverLater(request, outcome);
            return new ChargeResult(outcome.transactionId(), ChargeResult.Status.PROCESSING, null);
        });
    }

    /** The final outcome for a token (used directly in SYNC mode, or later in the webhook in WEBHOOK mode). */
    public static ChargeResult decide(String token) {
        String transactionId = "sim_txn_" + UUID.randomUUID().toString().replace("-", "");
        return switch (token == null ? "" : token) {
            case TOKEN_SUCCESS -> new ChargeResult(transactionId, ChargeResult.Status.SUCCEEDED, null);
            case TOKEN_DECLINE -> new ChargeResult(transactionId, ChargeResult.Status.FAILED, "Card declined");
            case TOKEN_INSUFFICIENT_FUNDS -> new ChargeResult(transactionId, ChargeResult.Status.FAILED, "Insufficient funds");
            default -> new ChargeResult(transactionId, ChargeResult.Status.FAILED, "Unsupported test payment method");
        };
    }
}
