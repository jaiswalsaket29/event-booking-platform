package org.saket.eventbooking.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Token buckets in Redis, shared by every app instance. Check-and-take runs as one Lua script
 * ({@code redis/token-bucket.lua}), so two concurrent requests can't both take the last token.
 * Identifiers (emails, IPs) are hashed before they become keys, so Redis holds no personal data.
 * If Redis is unreachable the request is allowed: rate limiting is protection, not a dependency.
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

    static final String KEY_PREFIX = "rate-limit:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public TokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Takes one token from {@code limit}'s bucket for {@code identifier}, or throws if it's empty. */
    public void consume(String limit, String identifier, RateLimitProperties.Bucket bucket) {
        String key = KEY_PREFIX + limit + ":" + hash(identifier);
        List<?> reply;
        try {
            reply = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(bucket.capacity()), String.valueOf(bucket.refillPeriod().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable; allowing the {} request", limit, e);
            return;
        }
        if (reply == null || reply.size() < 2) {
            log.warn("Unexpected rate limiter reply {}; allowing the {} request", reply, limit);
            return;
        }
        if (((Number) reply.get(0)).longValue() != 1) {
            throw new RateLimitExceededException(Duration.ofMillis(((Number) reply.get(1)).longValue()));
        }
    }

    static String hash(String identifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
