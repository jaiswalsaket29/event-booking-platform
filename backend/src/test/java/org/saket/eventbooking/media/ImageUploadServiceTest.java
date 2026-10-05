package org.saket.eventbooking.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.storage.R2ObjectStorage;
import org.saket.eventbooking.common.storage.StorageProperties;
import org.saket.eventbooking.common.storage.UnconfiguredObjectStorage;
import org.saket.eventbooking.media.dto.ImageUploadRequest;
import org.saket.eventbooking.media.dto.ImageUploadResponse;
import org.saket.eventbooking.media.enums.ImagePurpose;
import org.saket.eventbooking.media.service.ImageUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pre-signing is local (SigV4), so the R2 path is testable without network or real credentials. */
class ImageUploadServiceTest {

    private static final StorageProperties.R2 R2 = new StorageProperties.R2("acc123", "test-key-id",
            "test-secret", "media", "https://img.example.test/");
    private static final StorageProperties PROPERTIES =
            new StorageProperties(Duration.ofMinutes(10), DataSize.ofMegabytes(5), R2);

    private final R2ObjectStorage r2 = new R2ObjectStorage(R2);
    private final ImageUploadService service = new ImageUploadService(r2, PROPERTIES);

    @AfterEach
    void close() {
        r2.close();
    }

    @Test
    void presignsAPutUrlBoundToTheTypeAndSize() {
        ImageUploadResponse upload = service.presign(new ImageUploadRequest(ImagePurpose.EVENT, "IMAGE/PNG", 123_456));

        URI url = URI.create(upload.uploadUrl());
        assertThat(upload.method()).isEqualTo("PUT");
        assertThat(url.getHost()).isEqualTo("acc123.r2.cloudflarestorage.com");
        assertThat(url.getPath()).matches("/media/events/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(url.getQuery())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Signature=", "X-Amz-Expires=600")
                .contains("X-Amz-SignedHeaders=content-length;content-type;host");
        assertThat(url.getQuery()).doesNotContain("test-secret");
        assertThat(upload.headers()).containsEntry("content-type", "image/png").hasSize(1);
        assertThat(upload.imageUrl()).isEqualTo("https://img.example.test" + url.getPath().substring("/media".length()));
        assertThat(upload.expiresAt()).isBetween(Instant.now().plusSeconds(590), Instant.now().plusSeconds(610));
    }

    @Test
    void everyUploadGetsItsOwnKey() {
        var request = new ImageUploadRequest(ImagePurpose.ARTIST, "image/webp", 1000);
        String first = service.presign(request).imageUrl();
        String second = service.presign(request).imageUrl();
        assertThat(first).contains("/artists/").endsWith(".webp").isNotEqualTo(second);
    }

    @Test
    void rejectsOtherContentTypes() {
        for (String type : new String[]{"image/gif", "image/svg+xml", "text/html", "application/octet-stream"}) {
            assertThatThrownBy(() -> service.presign(new ImageUploadRequest(ImagePurpose.EVENT, type, 100)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("contentType");
        }
    }

    @Test
    void rejectsImagesOverTheSizeLimit() {
        long limit = DataSize.ofMegabytes(5).toBytes();
        service.presign(new ImageUploadRequest(ImagePurpose.EVENT, "image/jpeg", limit));
        assertThatThrownBy(() -> service.presign(new ImageUploadRequest(ImagePurpose.EVENT, "image/jpeg", limit + 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void withoutStorageTheOptionsSayUploadIsOffAndPresignIs501() {
        var fallback = new ImageUploadService(new UnconfiguredObjectStorage(), PROPERTIES);
        assertThat(fallback.options().directUpload()).isFalse();
        assertThat(fallback.options().allowedContentTypes()).containsExactly("image/jpeg", "image/png", "image/webp");
        assertThatThrownBy(() -> fallback.presign(new ImageUploadRequest(ImagePurpose.EVENT, "image/png", 100)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED));
    }
}
