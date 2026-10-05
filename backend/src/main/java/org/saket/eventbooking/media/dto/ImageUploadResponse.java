package org.saket.eventbooking.media.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Upload with {@code PUT uploadUrl}, sending {@code headers} unchanged and the file as the body; then save
 * {@code imageUrl} on the event or artist (the existing image-URL fields).
 */
public record ImageUploadResponse(
        String method,
        String uploadUrl,
        Map<String, String> headers,
        String imageUrl,
        Instant expiresAt) {
}
