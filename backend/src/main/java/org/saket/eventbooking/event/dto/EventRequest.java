package org.saket.eventbooking.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.saket.eventbooking.event.enums.EventStatus;

/** Create/update payload. A null {@code status} means DRAFT on create and "unchanged" on update. */
public record EventRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 10000) String description,
        @NotBlank @Size(max = 100) String category,
        @Size(max = 500) @Pattern(regexp = EventImageRequest.HTTP_URL, message = "must be an http(s) URL") String imageUrl,
        EventStatus status,
        @Size(max = 50) String language,
        @Positive Integer durationMinutes,
        @Size(max = 20) String ageRestriction,
        @Size(max = 5000) String highlights,
        @Size(max = 10000) String termsAndConditions,
        boolean featured) {
}
