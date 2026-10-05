package org.saket.eventbooking.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.service.PaymentAttemptService;
import org.saket.eventbooking.payment.service.PaymentService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CancelCheckoutTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAttemptService attemptService;
    @Autowired SessionService sessionService;
    @Autowired BookingHoldStore holdStore;

    private User user;
    private UUID eventId;
    private LocationResponse venue;

    @BeforeEach
    void setUp() {
        user = createUser(Role.USER, true);
        eventId = fixtures.publishedEvent(unique("Changed Mind")).id();
        venue = fixtures.location(unique("Exitville"));
    }

    private ResultActions cancel(User who, UUID bookingId) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("Authorization", bearer(who)));
    }

    private BookingResponse flatBooking() {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(6), 10, "400");
        return bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, 3, null));
    }

    @Test
    void cancellingReleasesSeatsImmediately() throws Exception {
        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 3);
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(6), "300");
        List<UUID> seats = sessionService.seatMap(session.id()).seats().stream().limit(2).map(s -> s.id()).toList();
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, null, seats));

        cancel(user, booking.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.holdExpiresAt").doesNotExist());

        assertThat(sessionService.seatMap(session.id()).seats())
                .allSatisfy(seat -> assertThat(seat.status()).isEqualTo(SessionSeatStatus.AVAILABLE));
        assertThat(holdStore.remainingTtl(booking.id())).isEmpty();
        cancel(user, booking.id()).andExpect(status().isConflict()); // already cancelled
    }

    @Test
    void cancellingAfterAFailedAttemptEndsAsFailed() throws Exception {
        BookingResponse booking = flatBooking();
        paymentService.pay(user.getId(), booking.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_decline"));

        cancel(user, booking.id()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(sessionService.get(booking.sessionId()).ticketsAvailable()).isEqualTo(10);
    }

    @Test
    void cancellingIsRefusedWhileAPaymentIsInFlightOrAfterConfirmation() throws Exception {
        BookingResponse inFlight = flatBooking();
        attemptService.open(user.getId(), inFlight.id(), UUID.randomUUID().toString());
        cancel(user, inFlight.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A payment for this booking is in progress; wait for it to finish"));

        BookingResponse paid = flatBooking();
        paymentService.pay(user.getId(), paid.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_success"));
        cancel(user, paid.id()).andExpect(status().isConflict());
    }

    @Test
    void onlyTheOwnerCanCancel() throws Exception {
        BookingResponse booking = flatBooking();
        cancel(createUser(Role.USER, true), booking.id()).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.id())).andExpect(status().isUnauthorized());
        assertThat(sessionService.get(booking.sessionId()).ticketsAvailable()).isEqualTo(7);
    }
}
