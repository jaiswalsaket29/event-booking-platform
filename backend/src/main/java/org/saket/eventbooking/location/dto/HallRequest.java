package org.saket.eventbooking.location.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Capacity is not part of the request: it is derived from the seat layout. */
public record HallRequest(@NotBlank @Size(max = 255) String name) {
}
