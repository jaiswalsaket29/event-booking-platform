package org.saket.eventbooking.session.service.seating;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** General admission with one capacity counter on the session row. */
@Component
public class FlatGeneralAdmissionStrategy implements SeatingStrategy {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean supports(Session session) {
        return session.getSeatingType() == SeatingType.GENERAL_ADMISSION && session.getPricingMode() == PricingMode.FLAT;
    }

    @Override
    public Hold hold(Session session, HoldRequest request) {
        if (request.ticketTierId() != null || !request.seatIds().isEmpty()) {
            throw new BadRequestException("This session sells unassigned tickets: send only a quantity");
        }
        int quantity = SeatingRules.requireQuantity(request.quantity());

        lockAndRefresh(session);
        if (session.getAvailableCapacity() < quantity) {
            throw new ConflictException(soldOutMessage(session.getAvailableCapacity()));
        }
        session.setAvailableCapacity(session.getAvailableCapacity() - quantity);

        return new Hold(session, null, quantity, List.of(),
                session.getBasePrice().multiply(BigDecimal.valueOf(quantity)));
    }

    @Override
    public void release(Session session, HeldInventory request) {
        lockAndRefresh(session);
        session.setAvailableCapacity(session.getAvailableCapacity() + request.quantity());
    }

    /**
     * {@code SELECT ... FOR UPDATE} and re-read. A plain locking query would hand back the instance
     * already in the persistence context with its possibly stale capacity; refresh re-reads it under
     * the lock, which is the value every other checkout is now waiting behind.
     * <p>
     * Flush first: refresh overwrites the in-memory entity with the row, which would silently throw
     * away this transaction's own unflushed changes to the session (e.g. the CANCELLED status set just
     * before a cancellation cascade releases capacity).
     */
    private void lockAndRefresh(Session session) {
        entityManager.flush();
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
    }

    static String soldOutMessage(int left) {
        return left == 0 ? "Sold out" : "Only " + left + " ticket" + (left == 1 ? "" : "s") + " left";
    }
}
