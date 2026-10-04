package org.saket.eventbooking.session.service.seating;

import java.util.List;
import java.util.UUID;

/**
 * What a booking is holding, as recorded on the booking: used to release it (hold ended unpaid)
 * or confirm it (paid). Only the fields for the session's mode are set.
 */
public record HeldInventory(UUID sessionId, UUID ticketTierId, Integer quantity, List<UUID> sessionSeatIds) {
}
