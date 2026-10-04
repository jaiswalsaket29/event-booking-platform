package org.saket.eventbooking.payment.gateway;

/**
 * The provider's immediate answer. {@code failureReason} is set only when FAILED.
 */
public record ChargeResult(String transactionId, Status status, String failureReason) {

    public enum Status {
        SUCCEEDED,
        FAILED,
        /** Outcome will arrive via webhook. */
        PROCESSING
    }

    public boolean isTerminal() {
        return status != Status.PROCESSING;
    }
}
