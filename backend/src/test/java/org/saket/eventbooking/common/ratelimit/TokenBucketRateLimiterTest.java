package org.saket.eventbooking.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Lua token bucket against a real Redis (Testcontainers). */
class TokenBucketRateLimiterTest extends IntegrationTest {

    @Autowired TokenBucketRateLimiter limiter;
    @Autowired StringRedisTemplate redis;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void allowsABurstUpToCapacityThenRefusesWithARetryAfter() {
        var bucket = new RateLimitProperties.Bucket(3, Duration.ofMinutes(1));
        String who = id();
        for (int i = 0; i < 3; i++) {
            limiter.consume("test", who, bucket);
        }
        assertThatThrownBy(() -> limiter.consume("test", who, bucket))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    // one token comes back every 20s
                    assertThat(e.getRetryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(20));
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 20L);
                });
    }

    @Test
    void bucketsAreIndependentPerIdentifierAndPerLimit() {
        var bucket = new RateLimitProperties.Bucket(1, Duration.ofMinutes(1));
        String who = id();
        limiter.consume("test", who, bucket);
        assertThatThrownBy(() -> limiter.consume("test", who, bucket)).isInstanceOf(RateLimitExceededException.class);

        limiter.consume("test", id(), bucket);   // someone else
        limiter.consume("other", who, bucket);   // same identifier, different limit
    }

    @Test
    void tokensRefillOverTime() throws Exception {
        var bucket = new RateLimitProperties.Bucket(2, Duration.ofMillis(400)); // a token every 200ms
        String who = id();
        limiter.consume("test", who, bucket);
        limiter.consume("test", who, bucket);
        assertThatThrownBy(() -> limiter.consume("test", who, bucket)).isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(250);
        limiter.consume("test", who, bucket);
        assertThatThrownBy(() -> limiter.consume("test", who, bucket)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void keysAreHashedAndExpire() {
        String email = "someone-" + id() + "@test.dev";
        limiter.consume("test", email, new RateLimitProperties.Bucket(5, Duration.ofMinutes(2)));

        String key = TokenBucketRateLimiter.KEY_PREFIX + "test:" + TokenBucketRateLimiter.hash(email);
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.keys(TokenBucketRateLimiter.KEY_PREFIX + "*" + email + "*")).isEmpty();
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 120L);
    }

    /** The check-and-take is one atomic script: 20 simultaneous requests at a bucket of 5 → exactly 5 pass. */
    @Test
    void concurrentRequestsNeverOverdrawTheBucket() throws Exception {
        var bucket = new RateLimitProperties.Bucket(5, Duration.ofMinutes(10));
        String who = id();
        int threads = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        limiter.consume("race", who, bucket);
                        return true;
                    } catch (RateLimitExceededException e) {
                        return false;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }
}
