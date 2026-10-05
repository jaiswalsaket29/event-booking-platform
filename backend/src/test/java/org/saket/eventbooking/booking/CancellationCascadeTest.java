package org.saket.eventbooking.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.booking.service.BookingStatsService;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.event.dto.EventRequest;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.service.PaymentAttemptService;
import org.saket.eventbooking.payment.service.PaymentService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.SessionUpdateRequest;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.saket.eventbooking.session.repository.SessionRepository;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.support.RecordingEmailService;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CancellationCascadeTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingStatsService statsService;
    @Autowired BookingHoldStore holdStore;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAttemptService attemptService;
    @Autowired SessionService sessionService;
    @Autowired SessionRepository sessionRepository;
    @Autowired EventService eventService;

    private String admin;
    private User paidCustomer;
    private User holdingCustomer;
    private UUID eventId;
    private String eventTitle;
    private LocationResponse venue;

    @BeforeEach
    void setUp() {
        admin = adminToken();
        paidCustomer = createUser(Role.USER, true);
        holdingCustomer = createUser(Role.USER, true);
        eventTitle = unique("Rained Out");
        eventId = fixtures.publishedEvent(eventTitle).id();
        venue = fixtures.location(unique("Floodplain"));
    }

    private BookingStatus statusOf(UUID bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getStatus();
    }

    private BookingResponse paid(User user, UUID sessionId, int quantity) {
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(sessionId, null, quantity, null));
        paymentService.pay(user.getId(), booking.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_success"));
        return booking;
    }

    private void cancelSessionAsAdmin(SessionResponse session) throws Exception {
        mockMvc.perform(put("/api/v1/admin/sessions/{id}", session.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startTime": "%s", "endTime": "%s", "status": "CANCELLED"}
                                """.formatted(session.startTime(), session.endTime())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private static Optional<RecordingEmailService.SentEmail> waitForEmail(RecordingEmailService emails, String to,
                                                                          String subjectPart) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Optional<RecordingEmailService.SentEmail> last = emails.lastTo(to);
            if (last.isPresent() && last.get().subject().contains(subjectPart)) {
                return last;
            }
            Thread.sleep(50);
        }
        return Optional.empty();
    }

    @Test
    void cancellingASessionCancelsItsBookingsAndFlagsRefunds() throws Exception {
        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 4);
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(7), "500");
        List<UUID> seats = sessionService.seatMap(session.id()).seats().stream().map(s -> s.id()).toList();

        BookingResponse confirmed = bookingService.create(paidCustomer.getId(),
                new CreateBookingRequest(session.id(), null, null, seats.subList(0, 2)));
        paymentService.pay(paidCustomer.getId(), confirmed.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_success"));
        BookingResponse holding = bookingService.create(holdingCustomer.getId(),
                new CreateBookingRequest(session.id(), null, null, seats.subList(2, 3)));
        long refundsBefore = statsService.stats(null, null).totals().paymentsNeedingRefund();

        cancelSessionAsAdmin(session);

        assertThat(statusOf(confirmed.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(statusOf(holding.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(holdStore.remainingTtl(holding.id())).isEmpty();
        assertThat(statsService.stats(null, null).totals().paymentsNeedingRefund()).isEqualTo(refundsBefore + 1);

        mockMvc.perform(get("/api/v1/admin/bookings").header("Authorization", admin)
                        .param("sessionId", session.id().toString()).param("needsAttention", "true"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].booking.id").value(confirmed.id().toString()));

        RecordingEmailService.SentEmail email = waitForEmail(emailService, paidCustomer.getEmail(), "Cancelled")
                .orElseThrow(() -> new AssertionError("no cancellation email"));
        assertThat(email.body()).contains(eventTitle).contains("will be refunded");

        // nothing more can be sold or paid for this session
        assertThatThrownBy(() -> bookingService.create(holdingCustomer.getId(),
                new CreateBookingRequest(session.id(), null, null, seats.subList(3, 4))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancellingAnEventCancelsItsUpcomingSessionsButNotCompletedOnes() throws Exception {
        SessionResponse first = fixtures.flatSession(eventId, venue.id(), daysFromNow(3), 50, "300");
        SessionResponse second = fixtures.flatSession(eventId, venue.id(), daysFromNow(4), 50, "300");
        SessionResponse done = fixtures.flatSession(eventId, venue.id(), daysFromNow(2), 50, "300");
        sessionService.update(done.id(), new SessionUpdateRequest(done.startTime(), done.endTime(), SessionStatus.COMPLETED));
        BookingResponse b1 = paid(paidCustomer, first.id(), 2);
        BookingResponse b2 = bookingService.create(holdingCustomer.getId(), new CreateBookingRequest(second.id(), null, 1, null));

        eventService.update(eventId, new EventRequest(eventTitle, null, "Music", null, EventStatus.CANCELLED,
                null, null, null, null, null, false));

        assertThat(sessionService.get(first.id()).status()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(sessionService.get(second.id()).status()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(sessionService.get(done.id()).status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(statusOf(b1.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(statusOf(b2.id())).isEqualTo(BookingStatus.CANCELLED);

        // the cascade can't be undone, so neither can the cancellation
        mockMvc.perform(put("/api/v1/admin/events/{id}", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"%s\", \"category\": \"Music\", \"status\": \"PUBLISHED\"}".formatted(eventTitle)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completedSessionsCanNotBeCancelled() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(2), 50, "300");
        sessionService.update(session.id(), new SessionUpdateRequest(session.startTime(), session.endTime(), SessionStatus.COMPLETED));
        mockMvc.perform(put("/api/v1/admin/sessions/{id}", session.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startTime": "%s", "endTime": "%s", "status": "CANCELLED"}
                                """.formatted(session.startTime(), session.endTime())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPaymentLandingAfterTheCancellationBecomesARefundNotATicket() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), 50, "300");
        BookingResponse booking = bookingService.create(holdingCustomer.getId(), new CreateBookingRequest(session.id(), null, 1, null));
        PaymentAttemptService.OpenedAttempt attempt = attemptService.open(holdingCustomer.getId(), booking.id(), UUID.randomUUID().toString());

        cancelSessionAsAdmin(session);
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);

        assertThat(attemptService.applyOutcome(attempt.paymentId(), "txn_" + UUID.randomUUID(),
                ChargeResult.Status.SUCCEEDED, null, null)).isEqualTo(PaymentAttemptService.Applied.LATE_PAYMENT);
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookingRepository.findById(booking.id()).orElseThrow().getBookingReference()).isNull();
    }

    /** The race the cascade can't see: a booking still PENDING on a session that is already cancelled. */
    @Test
    void aPendingBookingOnACancelledSessionIsNeverConfirmed() {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), 50, "300");
        BookingResponse booking = bookingService.create(holdingCustomer.getId(), new CreateBookingRequest(session.id(), null, 2, null));
        PaymentAttemptService.OpenedAttempt attempt = attemptService.open(holdingCustomer.getId(), booking.id(), UUID.randomUUID().toString());
        // flip the session's status behind the cascade's back
        var raw = sessionRepository.findById(session.id()).orElseThrow();
        raw.setStatus(SessionStatus.CANCELLED);
        sessionRepository.save(raw);

        assertThatThrownBy(() -> attemptService.open(holdingCustomer.getId(), booking.id(), UUID.randomUUID().toString()))
                .isInstanceOf(ConflictException.class);
        assertThat(attemptService.applyOutcome(attempt.paymentId(), "txn_" + UUID.randomUUID(),
                ChargeResult.Status.SUCCEEDED, null, null)).isEqualTo(PaymentAttemptService.Applied.LATE_PAYMENT);
        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);
    }
}
