package org.saket.eventbooking.booking.enums;

/**
 * Booking lifecycle:
 * <pre>
 *   PENDING -> CONFIRMED   payment succeeded
 *   PENDING -> FAILED      payment retries exhausted, or the hold timed out after a payment attempt
 *   PENDING -> CANCELLED   the hold timed out with no payment attempt (or the user/organiser cancelled)
 *   CONFIRMED -> CANCELLED the organiser cancelled the session or event (refund owed, handled outside the app)
 * </pre>
 * Nothing moves backward, and CANCELLED / FAILED are final.
 * {@link org.saket.eventbooking.booking.entity.Booking#transitionTo} is the only way to change a
 * booking's status, and it enforces this table.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED;

    /** No payment or expiry can change it any more (CONFIRMED can still be cancelled by the organiser). */
    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canTransitionTo(BookingStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> next != PENDING;
            case CONFIRMED -> next == CANCELLED;
            case CANCELLED, FAILED -> false;
        };
    }
}
