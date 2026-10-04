package org.saket.eventbooking.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Gallery image by URL (direct upload via pre-signed URLs comes later, Phase 6). */
public record EventImageRequest(
        @NotBlank @Size(max = 500) String imageUrl,
        @Min(0) int sortOrder) {
}
