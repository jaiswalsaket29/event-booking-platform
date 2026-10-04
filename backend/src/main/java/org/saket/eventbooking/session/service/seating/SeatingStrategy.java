package org.saket.eventbooking.session.service.seating;

import org.saket.eventbooking.session.entity.Session;

/**
 * One way of selling a session's inventory. Each implementation locks the rows it changes with
 * {@code SELECT ... FOR UPDATE} (JPA {@code PESSIMISTIC_WRITE}) so concurrent checkouts serialize on
 * exactly the contended inventory and nothing is oversold.
 * <p>
 * Callers must already be inside a transaction: the locks are held until it commits.
 */
public interface SeatingStrategy {

    /** Whether this strategy sells the given session (decided by seatingType / pricingMode only). */
    boolean supports(Session session);

    /** Validates the request for this mode, locks the inventory, and takes it out of circulation. */
    Hold hold(Session session, HoldRequest request);

    /**
     * Puts held inventory back. Callers guarantee it runs at most once per hold (the booking's
     * status transition out of PENDING is the guard), so counters can't be double-incremented.
     */
    void release(Session session, HeldInventory request);

    /**
     * Makes a paid hold permanent. General admission has nothing to do (the counter was already
     * decremented at hold time); assigned seating moves its seats LOCKED -> BOOKED.
     */
    default void confirm(Session session, HeldInventory held) {
    }
}
