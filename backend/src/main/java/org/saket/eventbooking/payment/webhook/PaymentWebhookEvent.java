package org.saket.eventbooking.payment.webhook;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Webhook payload.
 *
 * @param type          {@code payment.succeeded} or {@code payment.failed}
 * @param transactionId the provider's id for the charge (primary match, unique)
 * @param paymentId     our payment id, sent to the provider as the merchant reference; used when the
 *                      webhook beats our own write of the transaction id
 * @param amount        what the provider says it charged; must equal the payment amount
 */
public record PaymentWebhookEvent(String eventId, String type, String transactionId, UUID paymentId,
                                  BigDecimal amount, String failureReason) {

    public static final String SUCCEEDED = "payment.succeeded";
    public static final String FAILED = "payment.failed";
}
