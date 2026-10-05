package org.saket.eventbooking.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.config.BookingProperties;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.event.BookingCancelledByOrganiserEvent;
import org.saket.eventbooking.booking.event.BookingConfirmedEvent;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.repository.BookingSeatRepository;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.saket.eventbooking.session.service.SessionInventoryService;
import org.saket.eventbooking.session.service.seating.HeldInventory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every way a PENDING booking ends goes through here, always as "lock the booking row, check, then
 * touch inventory". Used by the payment domain (confirm / fail after retries) and by hold expiry.
 * All methods join the caller's transaction ({@code MANDATORY}): the booking lock must be held
 * across the caller's own changes (e.g. the payment row) until commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCheckoutService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final SessionInventoryService sessionInventoryService;
    private final BookingHoldStore holdStore;
    private final BookingProperties properties;
    private final ApplicationEventPublisher events;

    /**
     * Locks the caller's own booking for a new payment attempt. 404 if it isn't theirs; 409 unless it
     * is still PENDING with a live hold (retries are only allowed while the tickets are held).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking lockForPayment(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        switch (booking.getStatus()) {
            case CONFIRMED -> throw new ConflictException("This booking is already paid");
            case CANCELLED, FAILED -> throw new ConflictException(
                    "This booking is " + booking.getStatus().name().toLowerCase() + "; start a new booking");
            case PENDING -> {
                if (booking.getSession().getStatus() != SessionStatus.SCHEDULED) {
                    throw new ConflictException("This show is no longer on sale; start a new booking");
                }
                if (!isHoldAlive(booking)) {
                    throw new ConflictException("The hold on these tickets has expired; start a new booking");
                }
            }
        }
        return booking;
    }

    /** Locks the caller's own booking; 404 if it isn't theirs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking lockOwn(UUID bookingId, UUID userId) {
        return bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    }

    /** Locks any booking (system callers: payment outcomes, expiry). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Booking> lock(UUID bookingId) {
        return bookingRepository.findByIdForUpdate(bookingId);
    }

    /** PENDING -> CONFIRMED: reference, confirmation time, seats BOOKED, timer stopped, event published. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(Booking booking) {
        booking.transitionTo(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(Instant.now());
        booking.setBookingReference(BookingReferenceGenerator.next());
        sessionInventoryService.confirm(heldBy(booking));
        afterCommit(() -> holdStore.remove(booking.getId()));
        events.publishEvent(new BookingConfirmedEvent(booking.getId()));
        log.info("Booking {} confirmed as {}", booking.getId(), booking.getBookingReference());
    }

    /**
     * PENDING -> FAILED or CANCELLED, releasing the held inventory in the same transaction. Callers
     * check the booking is PENDING under the lock, so this runs at most once per booking even when
     * payment failure, the Redis expiry event and the sweeper race each other.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void endUnpaid(Booking booking, BookingStatus terminalStatus) {
        booking.transitionTo(terminalStatus);
        sessionInventoryService.release(heldBy(booking));
        afterCommit(() -> holdStore.remove(booking.getId()));
        log.info("Booking {} ended {}; tickets released", booking.getId(), terminalStatus);
    }

    /**
     * CONFIRMED -> CANCELLED because the organiser cancelled the session. Seats stay BOOKED (the session
     * is off sale, and they record what was sold). The successful payment now counts as a refund owed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelConfirmed(Booking booking) {
        booking.transitionTo(BookingStatus.CANCELLED);
        events.publishEvent(new BookingCancelledByOrganiserEvent(booking.getId()));
        log.info("Confirmed booking {} cancelled by the organiser; refund owed", booking.getId());
    }

    public boolean isHoldAlive(Booking booking) {
        boolean withinTtl = booking.getCreatedAt().plus(properties.holdTtl()).isAfter(Instant.now());
        return withinTtl && holdStore.remainingTtl(booking.getId()).isPresent();
    }

    private HeldInventory heldBy(Booking booking) {
        UUID tierId = booking.getTicketTier() != null ? booking.getTicketTier().getId() : null;
        return new HeldInventory(booking.getSession().getId(), tierId, booking.getQuantity(),
                bookingSeatRepository.findSessionSeatIdsByBookingId(booking.getId()));
    }

    /** Redis clean-up only after the DB commit; failure is harmless (a stale key expires into a no-op). */
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("Post-commit hold clean-up failed (harmless, the key will expire)", e);
                }
            }
        });
    }
}
