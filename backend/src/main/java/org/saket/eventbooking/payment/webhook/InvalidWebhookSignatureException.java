package org.saket.eventbooking.payment.webhook;

/** Missing or wrong webhook signature (401). The payload is never parsed in that case. */
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
