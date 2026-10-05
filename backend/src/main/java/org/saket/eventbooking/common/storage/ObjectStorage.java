package org.saket.eventbooking.common.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Where uploaded media lives. The backend never handles file bytes: it hands the browser a short-lived
 * pre-signed URL and the browser uploads straight to the bucket. When no storage is configured
 * ({@link UnconfiguredObjectStorage}), admins submit image URLs instead.
 */
public interface ObjectStorage {

    /** Whether {@link #presignUpload} works (credentials configured). */
    boolean supportsDirectUpload();

    /**
     * A URL the client can {@code PUT} exactly one object to, with exactly this content type and length
     * (both are part of the signature, so the bucket rejects anything else).
     */
    PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration validFor);

    /**
     * @param uploadUrl       pre-signed {@code PUT} URL
     * @param requiredHeaders headers the client must send unchanged (they're signed)
     * @param publicUrl       where the object can be read once uploaded; store this as the image URL
     */
    record PresignedUpload(String uploadUrl, Map<String, String> requiredHeaders, String publicUrl,
                           Instant expiresAt) {
    }
}
