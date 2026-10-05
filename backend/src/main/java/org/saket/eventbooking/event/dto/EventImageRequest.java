package org.saket.eventbooking.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Gallery image by URL: either one returned by {@code POST /api/v1/admin/uploads/images} after a direct
 * upload, or any public image URL when uploads aren't configured.
 */
public record EventImageRequest(
        @NotBlank @Size(max = 500) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String imageUrl,
        @Min(0) int sortOrder) {

    /** Image URLs end up in {@code src}/{@code href} attributes, so only http(s) is accepted. */
    public static final String HTTP_URL = "https?://\\S+";
}
