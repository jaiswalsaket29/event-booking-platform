package org.saket.eventbooking.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.saket.eventbooking.media.enums.ImagePurpose;

/** Ask for an upload URL for one image of this type and exact size (bytes). */
public record ImageUploadRequest(
        @NotNull ImagePurpose purpose,
        @NotBlank String contentType,
        @Positive long contentLength) {
}
