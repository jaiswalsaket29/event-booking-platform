package org.saket.eventbooking.location.dto;

import org.saket.eventbooking.location.entity.Seat;
import org.saket.eventbooking.location.enums.SeatType;

import java.util.UUID;

public record SeatResponse(UUID id, String rowLabel, int seatNumber, SeatType seatType) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getSeatType());
    }
}
