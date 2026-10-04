package org.saket.eventbooking.session.service.seating;

import java.util.List;
import java.util.UUID;

/**
 * What a customer wants to hold. Which fields apply depends on the session's mode:
 * flat GA uses {@code quantity}; tiered GA uses {@code ticketTierId} + {@code quantity};
 * assigned seating uses {@code sessionSeatIds}.
 */
public record HoldRequest(UUID ticketTierId, Integer quantity, List<UUID> sessionSeatIds) {

    public List<UUID> seatIds() {
        return sessionSeatIds == null ? List.of() : sessionSeatIds;
    }
}
