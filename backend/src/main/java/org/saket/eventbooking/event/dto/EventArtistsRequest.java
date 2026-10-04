package org.saket.eventbooking.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Replaces an event's whole line-up. An empty list clears it. */
public record EventArtistsRequest(@NotNull @Size(max = 50) List<@Valid @NotNull Entry> artists) {

    public record Entry(@NotNull UUID artistId, @Size(max = 100) String role) {
    }
}
