package org.saket.eventbooking.session.dto;

import jakarta.validation.constraints.NotNull;
import org.saket.eventbooking.session.enums.SessionStatus;

import java.time.Instant;

/**
 * Editable after creation: timing and status. Seating type, venue and prices are fixed once a session
 * exists (seat prices are frozen snapshots); delete and recreate the session to change them.
 */
public record SessionUpdateRequest(
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotNull SessionStatus status) {
}
