package org.saket.eventbooking.location.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.saket.eventbooking.location.enums.SeatType;

import java.util.List;

/**
 * Bulk seat layout for a hall, front to back. Each block adds {@code rows} rows of
 * {@code seatsPerRow} seats of one type; row labels continue across blocks (A, B, ... Z, AA, AB, ...).
 * Example: 8 rows x 12 REGULAR, then 3 rows x 10 PREMIUM, then 1 row x 6 RECLINER.
 */
public record SeatLayoutRequest(@NotEmpty @Size(max = 20) List<@Valid @NotNull Block> blocks) {

    public record Block(
            @Min(1) @Max(50) int rows,
            @Min(1) @Max(100) int seatsPerRow,
            @NotNull SeatType seatType) {
    }
}
