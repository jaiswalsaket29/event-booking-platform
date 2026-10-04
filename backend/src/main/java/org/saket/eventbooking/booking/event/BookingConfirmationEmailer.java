package org.saket.eventbooking.booking.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.repository.BookingSeatRepository;
import org.saket.eventbooking.common.email.EmailService;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.SessionSeat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Sends the confirmation email for a newly confirmed booking.
 * <ul>
 *   <li>{@code AFTER_COMMIT}: only for confirmations that actually committed (never for a rolled-back one).</li>
 *   <li>{@code @Async}: on a separate thread, so a slow mail server never delays the payment response
 *       or the webhook acknowledgement.</li>
 * </ul>
 * A failed email is logged, not retried: the booking stays confirmed and the tickets (and QR) are
 * always available in the app.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingConfirmationEmailer {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final EmailService emailService;

    @Value("${app.frontend.user-url:http://localhost:5173}")
    private String userFrontendUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onConfirmed(BookingConfirmedEvent event) {
        try {
            Booking booking = bookingRepository.findWithDetailsById(event.bookingId()).orElseThrow();
            emailService.send(booking.getUser().getEmail(),
                    "Your tickets for " + booking.getSession().getEvent().getTitle() + " (" + booking.getBookingReference() + ")",
                    body(booking));
        } catch (RuntimeException e) {
            log.error("Confirmation email for booking {} failed", event.bookingId(), e);
        }
    }

    private String body(Booking booking) {
        Session session = booking.getSession();
        String tickets = booking.getQuantity() != null
                ? booking.getQuantity() + " x " + (booking.getTicketTier() != null ? booking.getTicketTier().getName() : "General admission")
                : bookingSeatRepository.findByBookingIdsWithSeats(java.util.List.of(booking.getId())).stream()
                        .map(bs -> bs.getSessionSeat())
                        .sorted(Comparator.comparing(SessionSeat::getSeat, HallService.SEAT_ORDER))
                        .map(ss -> ss.getSeat().getRowLabel() + ss.getSeat().getSeatNumber())
                        .collect(Collectors.joining(", ", "Seats: ", ""));
        return """
                Hi %s,

                You're going! Your booking is confirmed.

                Booking reference: %s
                Event: %s
                When: %s
                Where: %s, %s%s
                %s
                Total paid: INR %s

                Show the QR code in your booking at the entrance:
                %s/bookings/%s
                """.formatted(
                booking.getUser().getName(),
                booking.getBookingReference(),
                session.getEvent().getTitle(),
                WHEN.format(session.getStartTime()),
                session.getLocation().getName(), session.getLocation().getCity(),
                session.getHall() != null ? " (" + session.getHall().getName() + ")" : "",
                tickets,
                booking.getTotalAmount().toPlainString(),
                userFrontendUrl, booking.getId());
    }
}
