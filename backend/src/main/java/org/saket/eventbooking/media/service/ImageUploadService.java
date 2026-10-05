package org.saket.eventbooking.media.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.storage.ObjectStorage;
import org.saket.eventbooking.common.storage.StorageProperties;
import org.saket.eventbooking.media.dto.ImageUploadOptionsResponse;
import org.saket.eventbooking.media.dto.ImageUploadRequest;
import org.saket.eventbooking.media.dto.ImageUploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Issues pre-signed image upload URLs after checking type and size. The object key is generated here
 * (never taken from the client), so uploads can't overwrite each other or escape their folder.
 */
@Service
@RequiredArgsConstructor
public class ImageUploadService {

    /** Allowed types and the file extension each gets in the bucket. */
    static final Map<String, String> CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final ObjectStorage storage;
    private final StorageProperties properties;
    private final Clock clock = Clock.systemUTC();

    public ImageUploadOptionsResponse options() {
        return new ImageUploadOptionsResponse(storage.supportsDirectUpload(),
                CONTENT_TYPES.keySet().stream().sorted().toList(), properties.maxImageSize().toBytes());
    }

    public ImageUploadResponse presign(ImageUploadRequest request) {
        if (!storage.supportsDirectUpload()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Direct image upload isn't configured on this server; submit an image URL instead");
        }
        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        String extension = CONTENT_TYPES.get(contentType);
        if (extension == null) {
            throw new BadRequestException("contentType must be one of " + List.copyOf(CONTENT_TYPES.keySet()));
        }
        long maxBytes = properties.maxImageSize().toBytes();
        if (request.contentLength() > maxBytes) {
            throw new BadRequestException("Image is too large: the limit is " + maxBytes + " bytes");
        }

        LocalDate today = LocalDate.now(clock);
        String key = "%s/%d/%02d/%s.%s".formatted(request.purpose().folder(), today.getYear(), today.getMonthValue(),
                UUID.randomUUID(), extension);
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, contentType, request.contentLength(),
                properties.uploadUrlTtl());
        return new ImageUploadResponse("PUT", upload.uploadUrl(), upload.requiredHeaders(), upload.publicUrl(),
                upload.expiresAt());
    }
}
