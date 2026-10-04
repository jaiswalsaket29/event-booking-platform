package org.saket.eventbooking.booking.enums;

/**
 * Booking lifecycle. PENDING is the only non-terminal state:
 * <pre>
 *   PENDING -> CONFIRMED   payment succeeded
 *   PENDING -> FAILED      payment retries exhausted, or the hold timed out after a payment attempt
 *   PENDING -> CANCELLED   the hold timed out with no payment attempt
 * </pre>
 * Nothing moves backward. {@link org.saket.eventbooking.booking.entity.Booking#transitionTo} is the only
 * way to change a booking's status, and it enforces this table.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canTransitionTo(BookingStatus next) {
        return this == PENDING && next != null && next != PENDING;
    }
}
