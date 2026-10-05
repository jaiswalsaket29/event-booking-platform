package org.saket.eventbooking.booking.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.common.email.EmailService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Tells a ticket holder their show was cancelled. After commit and async, like the confirmation email. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCancellationEmailer {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    private final BookingRepository bookingRepository;
    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onCancelled(BookingCancelledByOrganiserEvent event) {
        try {
            Booking booking = bookingRepository.findWithDetailsById(event.bookingId()).orElseThrow();
            var session = booking.getSession();
            emailService.send(booking.getUser().getEmail(),
                    "Cancelled: " + session.getEvent().getTitle() + " (" + booking.getBookingReference() + ")",
                    """
                    Hi %s,

                    We're sorry: %s on %s at %s has been cancelled by the organiser.
                    Your booking %s is cancelled and your payment of INR %s will be refunded.
                    """.formatted(booking.getUser().getName(), session.getEvent().getTitle(),
                            WHEN.format(session.getStartTime()), session.getLocation().getName(),
                            booking.getBookingReference(), booking.getTotalAmount().toPlainString()));
        } catch (RuntimeException e) {
            log.error("Cancellation email for booking {} failed", event.bookingId(), e);
        }
    }
}
