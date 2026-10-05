package org.saket.eventbooking.common.storage;

import java.time.Duration;

/** Used when no R2 credentials are set: direct upload is off and images are added by URL. */
public class UnconfiguredObjectStorage implements ObjectStorage {

    @Override
    public boolean supportsDirectUpload() {
        return false;
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration validFor) {
        throw new UnsupportedOperationException("No object storage is configured");
    }
}
