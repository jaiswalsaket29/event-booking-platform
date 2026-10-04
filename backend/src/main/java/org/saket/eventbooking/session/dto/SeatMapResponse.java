package org.saket.eventbooking.session.dto;

import org.saket.eventbooking.location.enums.SeatType;
import org.saket.eventbooking.session.enums.SessionSeatStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Seat map for an assigned-seating session, front to back. Each seat's {@code id} is the
 * {@code SessionSeat} id that booking requests will reference.
 */
public record SeatMapResponse(UUID sessionId, UUID hallId, String hallName, List<SeatEntry> seats) {

    public record SeatEntry(UUID id, String rowLabel, int seatNumber, SeatType seatType,
                            SessionSeatStatus status, BigDecimal price) {
    }
}
