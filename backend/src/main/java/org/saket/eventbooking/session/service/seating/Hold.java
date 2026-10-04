package org.saket.eventbooking.session.service.seating;

import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.SessionSeat;
import org.saket.eventbooking.session.entity.TicketTier;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inventory that has been taken out of circulation for one checkout. {@code ticketTier} is set only
 * for tiered GA, {@code quantity} only for GA, {@code seats} only for assigned seating.
 */
public record Hold(Session session, TicketTier ticketTier, Integer quantity, List<SessionSeat> seats,
                   BigDecimal totalAmount) {
}
