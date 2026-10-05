package org.saket.eventbooking.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ArtistRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 5000) String bio,
        @Size(max = 500) @Pattern(regexp = EventImageRequest.HTTP_URL, message = "must be an http(s) URL") String imageUrl,
        @Size(max = 100) String genre) {
}
