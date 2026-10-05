package org.saket.eventbooking.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.cache.*}.
 *
 * @param eventListTtl   lifetime of a cached public listing page. Kept short because listings also go stale
 *                       without any write: a session that starts drops out of "upcoming".
 * @param eventDetailTtl lifetime of a cached event detail (event, line-up, images); admin edits evict it
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(Duration eventListTtl, Duration eventDetailTtl) {
}
