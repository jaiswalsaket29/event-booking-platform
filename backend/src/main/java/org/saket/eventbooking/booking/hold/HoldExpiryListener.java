package org.saket.eventbooking.booking.hold;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Receives Redis "key expired" events (channel {@code __keyevent@<db>__:expired}, body = key name)
 * and expires the matching booking.
 * <p>
 * Redis pub/sub is fire-and-forget: if the app is down when a key expires, the event is lost.
 * {@link StaleHoldSweeper} covers that case.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryListener implements MessageListener {

    private final BookingExpiryService bookingExpiryService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        BookingHoldStore.bookingIdFromKey(key).ifPresent(bookingId -> {
            try {
                bookingExpiryService.expire(bookingId);
            } catch (RuntimeException e) {
                // Don't kill the listener thread; the sweeper will retry this booking.
                log.warn("Failed to expire booking {} from Redis event; the sweeper will retry", bookingId, e);
            }
        });
    }
}
