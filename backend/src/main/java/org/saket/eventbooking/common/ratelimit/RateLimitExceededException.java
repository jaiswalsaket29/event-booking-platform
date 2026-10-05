package org.saket.eventbooking.common.ratelimit;

import lombok.Getter;

import java.time.Duration;

/** Mapped to 429 Too Many Requests with a {@code Retry-After} header. */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many requests; try again in " + retryAfterSeconds(retryAfter) + " seconds");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, at least 1 (the unit of the Retry-After header). */
    public long retryAfterSeconds() {
        return retryAfterSeconds(retryAfter);
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
