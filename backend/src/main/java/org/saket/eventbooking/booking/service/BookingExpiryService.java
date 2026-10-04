package org.saket.eventbooking.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.payment.service.PaymentQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Ends holds that ran out of time. Called by the Redis key-expiry listener and by the safety-net
 * sweeper, possibly both, possibly on several app instances at once, so it must be idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingExpiryService {

    private final BookingCheckoutService checkoutService;
    private final PaymentQueryService paymentQueryService;

    /**
     * Ends a still-PENDING booking and puts its tickets back on sale: FAILED if a payment was attempted,
     * CANCELLED if not (per the state machine in design-decisions.md).
     * <p>
     * Idempotency comes from the booking row lock: every caller does "lock row, check PENDING,
     * transition, release" in one transaction. The second caller blocks on the lock, then sees the
     * booking is no longer PENDING and does nothing, so inventory is released exactly once.
     * Payment outcomes take the same lock first, so a late payment and an expiry can't both win.
     *
     * @return true if this call performed the transition
     */
    @Transactional
    public boolean expire(UUID bookingId) {
        Optional<Booking> locked = checkoutService.lock(bookingId);
        if (locked.isEmpty() || locked.get().getStatus() != BookingStatus.PENDING) {
            return false;
        }
        boolean attempted = paymentQueryService.countAttempts(bookingId) > 0;
        checkoutService.endUnpaid(locked.get(), attempted ? BookingStatus.FAILED : BookingStatus.CANCELLED);
        log.info("Hold expired for booking {}", bookingId);
        return true;
    }
}
