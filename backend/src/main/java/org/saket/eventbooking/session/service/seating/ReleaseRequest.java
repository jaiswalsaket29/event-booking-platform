package org.saket.eventbooking.session.service.seating;

import java.util.List;
import java.util.UUID;

/** Inventory to put back after a hold ends without a purchase (mirror of what was held). */
public record ReleaseRequest(UUID sessionId, UUID ticketTierId, Integer quantity, List<UUID> sessionSeatIds) {
}
