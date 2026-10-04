package org.saket.eventbooking.booking;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.service.PaymentService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.support.RecordingEmailService;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketIssuingTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired PaymentService paymentService;
    @Autowired BookingRepository bookingRepository;
    @Autowired SessionService sessionService;

    private BookingResponse confirmedSeatBooking(User user) {
        UUID eventId = fixtures.publishedEvent(unique("Ticketed")).id();
        LocationResponse venue = fixtures.location(unique("Gatehouse"));
        HallDetailResponse hall = fixtures.hall(venue.id(), 2, 4);
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(9), "350");
        List<UUID> seats = sessionService.seatMap(session.id()).seats().subList(2, 4).stream().map(s -> s.id()).toList();
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, null, seats));
        paymentService.pay(user.getId(), booking.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_success"));
        return booking;
    }

    private static Optional<RecordingEmailService.SentEmail> waitForEmail(RecordingEmailService emails, String to) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (emails.lastTo(to).isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        return emails.lastTo(to);
    }

    @Test
    void qrCodeEncodesTheBookingReference() throws Exception {
        User user = createUser(Role.USER, true);
        BookingResponse booking = confirmedSeatBooking(user);
        String reference = bookingRepository.findById(booking.id()).orElseThrow().getBookingReference();

        byte[] png = mockMvc.perform(get("/api/v1/bookings/{id}/qr", booking.id()).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();

        var image = ImageIO.read(new ByteArrayInputStream(png));
        String decoded = new QRCodeReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        assertThat(decoded).isEqualTo(reference).matches("EVT-[0-9A-Z]{12}");
    }

    @Test
    void confirmationEmailIsSentAsynchronouslyAfterCommit() throws Exception {
        User user = createUser(Role.USER, true);
        BookingResponse booking = confirmedSeatBooking(user);
        String reference = bookingRepository.findById(booking.id()).orElseThrow().getBookingReference();

        RecordingEmailService.SentEmail email = waitForEmail(emailService, user.getEmail()).orElseThrow(
                () -> new AssertionError("no confirmation email"));
        assertThat(email.subject()).contains(reference);
        assertThat(email.thread()).isNotEqualTo(Thread.currentThread().getName()); // really @Async
        assertThat(email.body())
                .contains("Booking reference: " + reference)
                .contains("Seats: A3, A4")
                .contains("Total paid: INR 700.00")
                .contains("/bookings/" + booking.id());
    }

    @Test
    void noEmailOrTicketForUnpaidBookings() throws Exception {
        User user = createUser(Role.USER, true);
        UUID eventId = fixtures.publishedEvent(unique("Unpaid")).id();
        SessionResponse session = fixtures.flatSession(eventId, fixtures.location(unique("Nowhere")).id(),
                daysFromNow(9), 10, "100");
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, 1, null));
        paymentService.pay(user.getId(), booking.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_decline"));

        mockMvc.perform(get("/api/v1/bookings/{id}/qr", booking.id()).header("Authorization", bearer(user)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/bookings/{id}/qr", booking.id()).header("Authorization", userToken()))
                .andExpect(status().isNotFound());
        Thread.sleep(300); // give a wrongly-sent async email time to show up
        assertThat(emailService.countTo(user.getEmail())).isZero();
    }
}
