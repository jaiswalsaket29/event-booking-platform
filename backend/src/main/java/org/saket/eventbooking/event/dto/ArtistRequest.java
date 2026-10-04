package org.saket.eventbooking.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ArtistRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 5000) String bio,
        @Size(max = 500) String imageUrl,
        @Size(max = 100) String genre) {
}
