package org.saket.eventbooking.payment.enums;

/**
 * Payment attempt lifecycle: {@code PENDING -> SUCCESS | FAILED}, both terminal.
 * {@code REFUNDED} exists in the schema, but refunds are out of scope, so no transition reaches it.
 * {@link org.saket.eventbooking.payment.entity.Payment#transitionTo} enforces this table.
 */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return this == PENDING && (next == SUCCESS || next == FAILED);
    }
}
