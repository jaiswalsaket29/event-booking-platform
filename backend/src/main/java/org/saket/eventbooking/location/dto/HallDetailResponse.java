package org.saket.eventbooking.location.dto;

import java.util.List;
import java.util.UUID;

/** A hall with its full physical seat layout, ordered row by row. */
public record HallDetailResponse(
        UUID id,
        LocationResponse location,
        String name,
        int totalCapacity,
        List<SeatResponse> seats) {
}
