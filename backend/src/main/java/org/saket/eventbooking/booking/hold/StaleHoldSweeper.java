package org.saket.eventbooking.booking.hold;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.config.BookingProperties;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safety net for missed Redis expiry events (app restarting when the key expired, Redis restarted,
 * notifications disabled). Every minute it expires PENDING bookings older than hold TTL + grace.
 * The listener remains the primary, near-instant path; this only bounds the worst case.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleHoldSweeper {

    private final BookingRepository bookingRepository;
    private final BookingExpiryService bookingExpiryService;
    private final BookingProperties properties;

    /** Off in tests, which call {@link #expireStale(Instant)} directly with a chosen "now". */
    @Value("${app.booking.sweeper.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.booking.sweeper.interval:60s}",
            initialDelayString = "${app.booking.sweeper.interval:60s}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        int expired = expireStale(Instant.now());
        if (expired > 0) {
            log.info("Sweeper expired {} stale hold(s) that Redis events didn't catch", expired);
        }
    }

    /** Each booking is expired in its own transaction, so one failure doesn't block the rest. */
    public int expireStale(Instant now) {
        Instant cutoff = now.minus(properties.holdTtl()).minus(properties.sweepGrace());
        List<UUID> stale = bookingRepository.findIdsByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoff);
        int expired = 0;
        for (UUID bookingId : stale) {
            try {
                if (bookingExpiryService.expire(bookingId)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Sweeper failed to expire booking {}", bookingId, e);
            }
        }
        return expired;
    }
}
