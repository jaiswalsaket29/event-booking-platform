package org.saket.eventbooking.media.dto;

import java.util.List;

/**
 * Tells the admin UI whether to show a file picker ({@code directUpload}) or an image-URL field.
 */
public record ImageUploadOptionsResponse(
        boolean directUpload,
        List<String> allowedContentTypes,
        long maxBytes) {
}
