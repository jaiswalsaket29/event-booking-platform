package org.saket.eventbooking.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.repository.BookingSeatRepository;
import org.saket.eventbooking.session.service.SessionInventoryService;
import org.saket.eventbooking.session.service.seating.ReleaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final SessionInventoryService sessionInventoryService;

    /**
     * Moves a PENDING booking to CANCELLED and puts its tickets back on sale.
     * <p>
     * Idempotency comes from the booking row lock: every caller does "lock row, check PENDING,
     * transition, release" in one transaction. The second caller blocks on the lock, then sees the
     * booking is no longer PENDING and does nothing, so inventory is released exactly once.
     * Lock order is always booking row first, then inventory rows (the same order payment
     * confirmation will use), which avoids deadlocks between expiry and confirmation.
     *
     * @return true if this call performed the transition
     */
    @Transactional
    public boolean expire(UUID bookingId) {
        Optional<Booking> locked = bookingRepository.findByIdForUpdate(bookingId);
        if (locked.isEmpty() || locked.get().getStatus() != BookingStatus.PENDING) {
            return false;
        }
        Booking booking = locked.get();
        // No payment attempts exist yet (Phase 5). Per design: timeout with no attempt -> CANCELLED;
        // Phase 5 will choose FAILED here when an attempt was made.
        booking.transitionTo(BookingStatus.CANCELLED);

        List<UUID> seatIds = bookingSeatRepository.findSessionSeatIdsByBookingId(bookingId);
        UUID tierId = booking.getTicketTier() != null ? booking.getTicketTier().getId() : null;
        sessionInventoryService.release(new ReleaseRequest(
                booking.getSession().getId(), tierId, booking.getQuantity(), seatIds));

        log.info("Hold expired: booking {} cancelled and its tickets released", bookingId);
        return true;
    }
}
