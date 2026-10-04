package org.saket.eventbooking.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Start a checkout. Send the fields for the session's mode:
 * flat GA {@code quantity}; tiered GA {@code ticketTierId} + {@code quantity};
 * assigned seating {@code sessionSeatIds} (ids from the session's seat map).
 */
public record CreateBookingRequest(
        @NotNull UUID sessionId,
        UUID ticketTierId,
        @Min(1) Integer quantity,
        @Size(max = 50) List<@NotNull UUID> sessionSeatIds) {
}
