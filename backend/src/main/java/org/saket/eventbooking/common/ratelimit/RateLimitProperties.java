package org.saket.eventbooking.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.rate-limit.*}: one token bucket per (limit, key). A bucket holds up to {@code capacity}
 * requests (the burst) and refills continuously, completely in {@code refillPeriod}.
 *
 * @param enabled turn every limit off (e.g. for load tests)
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Bucket loginPerIp,
        Bucket loginPerEmail,
        Bucket forgotPasswordPerIp,
        Bucket forgotPasswordPerEmail,
        Bucket bookingPerUser) {

    public record Bucket(int capacity, Duration refillPeriod) {
        public Bucket {
            if (capacity < 1 || refillPeriod == null || refillPeriod.toMillis() < 1) {
                throw new IllegalArgumentException("A rate-limit bucket needs capacity >= 1 and a positive refill period");
            }
        }
    }
}
