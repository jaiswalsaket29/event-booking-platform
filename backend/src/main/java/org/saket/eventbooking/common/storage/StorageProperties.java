package org.saket.eventbooking.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * {@code app.storage.*}.
 *
 * @param uploadUrlTtl  how long a pre-signed upload URL stays valid
 * @param maxImageSize  largest image accepted for upload
 * @param r2            Cloudflare R2 credentials; when any value is blank, direct upload is off
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(Duration uploadUrlTtl, DataSize maxImageSize, R2 r2) {

    /**
     * @param publicBaseUrl base URL objects are served from (r2.dev subdomain or custom domain), no trailing slash
     */
    public record R2(String accountId, String accessKeyId, String secretAccessKey, String bucket,
                     String publicBaseUrl) {

        public boolean isConfigured() {
            return Stream.of(accountId, accessKeyId, secretAccessKey, bucket, publicBaseUrl)
                    .allMatch(StringUtils::hasText);
        }
    }
}
