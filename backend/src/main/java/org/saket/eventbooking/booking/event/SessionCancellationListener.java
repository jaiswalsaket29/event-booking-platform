package org.saket.eventbooking.booking.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingCheckoutService;
import org.saket.eventbooking.session.event.SessionCancelledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The booking side of a session cancellation, run synchronously inside the cancelling transaction:
 * <ul>
 *   <li>PENDING bookings end as CANCELLED and their holds are released (a payment that succeeds
 *       afterwards is recorded as a refund owed);</li>
 *   <li>CONFIRMED bookings become CANCELLED; their payment is now a refund owed, and the customer is
 *       emailed after commit.</li>
 * </ul>
 * Each booking is locked (in id order) before it changes, exactly like expiry and payment outcomes,
 * so a cancellation racing a payment or an expiry still settles each booking exactly once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCancellationListener {

    private final BookingRepository bookingRepository;
    private final BookingCheckoutService checkoutService;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSessionCancelled(SessionCancelledEvent event) {
        List<UUID> affected = bookingRepository.findIdsBySessionIdAndStatusIn(event.sessionId(),
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED));
        int pending = 0;
        int confirmed = 0;
        for (UUID bookingId : affected) {
            Booking booking = checkoutService.lock(bookingId).orElseThrow();
            // Re-checked under the lock: it may have settled since the id query.
            switch (booking.getStatus()) {
                case PENDING -> {
                    checkoutService.endUnpaid(booking, BookingStatus.CANCELLED);
                    pending++;
                }
                case CONFIRMED -> {
                    checkoutService.cancelConfirmed(booking);
                    confirmed++;
                }
                default -> {
                }
            }
        }
        log.info("Session {} cancelled: {} pending and {} confirmed bookings cancelled",
                event.sessionId(), pending, confirmed);
    }
}
