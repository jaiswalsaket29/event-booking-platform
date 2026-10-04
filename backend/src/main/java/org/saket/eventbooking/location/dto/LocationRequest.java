package org.saket.eventbooking.location.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.saket.eventbooking.location.enums.VenueType;

public record LocationRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String address,
        @NotBlank @Size(max = 255) String city,
        @NotNull VenueType venueType) {
}
