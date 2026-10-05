package org.saket.eventbooking.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /** R2 when all its settings are present; otherwise uploads are off and images are added by URL. */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    public ObjectStorage objectStorage(StorageProperties properties) {
        StorageProperties.R2 r2 = properties.r2();
        if (r2 != null && r2.isConfigured()) {
            log.info("Image uploads go to R2 bucket '{}'", r2.bucket());
            return new R2ObjectStorage(r2);
        }
        log.info("No R2 credentials configured: direct image upload is off, images are added by URL");
        return new UnconfiguredObjectStorage();
    }
}
