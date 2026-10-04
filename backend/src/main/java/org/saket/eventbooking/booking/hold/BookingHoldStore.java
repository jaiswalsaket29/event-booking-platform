package org.saket.eventbooking.booking.hold;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * One Redis key per open hold: {@code booking-hold:<bookingId>} with the hold TTL. Redis expires the
 * key on time and publishes a key-expired event, which {@link HoldExpiryListener} turns into a release.
 * The database stays the source of truth for what is held; the key is the timer.
 */
@Component
@RequiredArgsConstructor
public class BookingHoldStore {

    public static final String KEY_PREFIX = "booking-hold:";

    private final StringRedisTemplate redis;

    public void place(UUID bookingId, Duration ttl) {
        redis.opsForValue().set(key(bookingId), bookingId.toString(), ttl);
    }

    /** Ends the timer early (e.g. once the booking is confirmed or cancelled). Safe if already gone. */
    public void remove(UUID bookingId) {
        redis.delete(key(bookingId));
    }

    /** Remaining TTL, empty if there is no live hold key. */
    public Optional<Duration> remainingTtl(UUID bookingId) {
        Long seconds = redis.getExpire(key(bookingId));
        return seconds == null || seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    }

    public static String key(UUID bookingId) {
        return KEY_PREFIX + bookingId;
    }

    /** The booking id inside an expired key's name, or empty for keys that aren't holds. */
    public static Optional<UUID> bookingIdFromKey(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(key.substring(KEY_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
