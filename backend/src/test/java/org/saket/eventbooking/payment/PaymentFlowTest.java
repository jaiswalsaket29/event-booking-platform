package org.saket.eventbooking.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.enums.PaymentStatus;
import org.saket.eventbooking.payment.repository.PaymentRepository;
import org.saket.eventbooking.payment.service.PaymentService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments with the simulated gateway in SYNC mode (outcomes apply immediately). */
class PaymentFlowTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingExpiryService expiryService;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingHoldStore holdStore;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentService paymentService;
    @Autowired SessionService sessionService;

    private User user;
    private UUID eventId;
    private LocationResponse venue;

    @BeforeEach
    void setUp() {
        user = createUser(Role.USER, true);
        eventId = fixtures.publishedEvent(unique("Paid Show")).id();
        venue = fixtures.location(unique("Cashville"));
    }

    private BookingResponse flatBooking(int capacity, int quantity) {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), capacity, "750");
        return bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, quantity, null));
    }

    private ResultActions pay(UUID bookingId, String key, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/{id}/payments", bookingId)
                .header("Authorization", bearer(user))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodToken\": \"%s\"}".formatted(token)));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private Booking reload(UUID bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow();
    }

    @Test
    void successfulPaymentConfirmsTheBooking() throws Exception {
        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 3);
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(5), "400");
        List<UUID> seats = sessionService.seatMap(session.id()).seats().stream().limit(2).map(s -> s.id()).toList();
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, null, seats));

        pay(booking.id(), key(), "tok_success")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(800))
                .andExpect(jsonPath("$.transactionId", matchesPattern("sim_txn_[0-9a-f]{32}")))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingReference", matchesPattern("EVT-[0-9A-HJKMNP-TV-Z]{12}")));

        Booking confirmed = reload(booking.id());
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(holdStore.remainingTtl(booking.id())).isEmpty();
        assertThat(sessionService.seatMap(session.id()).seats().stream().limit(2))
                .allSatisfy(seat -> assertThat(seat.status()).isEqualTo(SessionSeatStatus.BOOKED));

        // a confirmed booking is untouched by a late expiry, and can't be paid twice
        assertThat(expiryService.expire(booking.id())).isFalse();
        pay(booking.id(), key(), "tok_success").andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.id()).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.holdExpiresAt").doesNotExist());
    }

    @Test
    void sameIdempotencyKeyReturnsTheSamePaymentWithoutChargingAgain() throws Exception {
        BookingResponse booking = flatBooking(10, 2);
        String key = key();

        String first = pay(booking.id(), key, "tok_success").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = pay(booking.id(), key, "tok_success").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) com.jayway.jsonpath.JsonPath.read(replay, "$.id"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.read(first, "$.id"));
        assertThat(paymentRepository.countByBookingId(booking.id())).isEqualTo(1);
    }

    @Test
    void declinedPaymentKeepsTheHoldAndARetryCanSucceed() throws Exception {
        BookingResponse booking = flatBooking(10, 2);

        pay(booking.id(), key(), "tok_decline")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Card declined"))
                .andExpect(jsonPath("$.bookingStatus").value("PENDING"));
        assertThat(holdStore.remainingTtl(booking.id())).isPresent();

        pay(booking.id(), key(), "tok_success")
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.id()).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[1].status").value("SUCCESS"));
    }

    @Test
    void exhaustingRetriesFailsTheBookingAndReleasesTickets() throws Exception {
        BookingResponse booking = flatBooking(10, 4);
        UUID sessionId = booking.sessionId();
        assertThat(sessionService.get(sessionId).ticketsAvailable()).isEqualTo(6);

        pay(booking.id(), key(), "tok_decline").andExpect(jsonPath("$.bookingStatus").value("PENDING"));
        pay(booking.id(), key(), "tok_insufficient_funds").andExpect(jsonPath("$.bookingStatus").value("PENDING"));
        pay(booking.id(), key(), "tok_decline")
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.bookingStatus").value("FAILED"));

        assertThat(sessionService.get(sessionId).ticketsAvailable()).isEqualTo(10);
        assertThat(holdStore.remainingTtl(booking.id())).isEmpty();
        pay(booking.id(), key(), "tok_success").andExpect(status().isConflict());
        // the release already happened; a late expiry event is a no-op
        assertThat(expiryService.expire(booking.id())).isFalse();
        assertThat(sessionService.get(sessionId).ticketsAvailable()).isEqualTo(10);
    }

    @Test
    void holdExpiryAfterAnAttemptFailsTheBookingInsteadOfCancelling() throws Exception {
        BookingResponse attempted = flatBooking(10, 1);
        pay(attempted.id(), key(), "tok_decline");
        expiryService.expire(attempted.id());
        assertThat(reload(attempted.id()).getStatus()).isEqualTo(BookingStatus.FAILED);

        BookingResponse untouched = flatBooking(10, 1);
        expiryService.expire(untouched.id());
        assertThat(reload(untouched.id()).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void paymentIsRefusedOnceTheHoldTimerIsGone() throws Exception {
        BookingResponse booking = flatBooking(10, 1);
        holdStore.remove(booking.id()); // Redis says time's up; the expiry event may not have landed yet
        pay(booking.id(), key(), "tok_success")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The hold on these tickets has expired; start a new booking"));
    }

    @Test
    void requestsAreValidatedAndScopedToTheOwner() throws Exception {
        BookingResponse booking = flatBooking(10, 1);
        BookingResponse other = flatBooking(10, 1);
        String usedKey = key();
        pay(other.id(), usedKey, "tok_decline").andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.id())
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\": \"tok_success\"}"))
                .andExpect(status().isBadRequest()); // no Idempotency-Key
        pay(booking.id(), "short", "tok_success").andExpect(status().isBadRequest());
        pay(booking.id(), key(), "").andExpect(status().isBadRequest());
        // the same key can't be replayed against a different booking
        pay(booking.id(), usedKey, "tok_success").andExpect(status().isConflict());

        // someone else's booking doesn't exist as far as this user can tell
        User stranger = createUser(Role.USER, true);
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.id())
                        .header("Authorization", bearer(stranger))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\": \"tok_success\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.id()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentRequestsWithOneKeyCreateOnePayment() throws Exception {
        BookingResponse booking = flatBooking(10, 2);
        String key = key();
        List<Future<PaymentService.PayResult>> results = race(12, () ->
                paymentService.pay(user.getId(), booking.id(), key, new PaymentRequest("tok_success")));

        Set<UUID> paymentIds = new HashSet<>();
        int created = 0;
        for (Future<PaymentService.PayResult> result : results) {
            PaymentService.PayResult r = result.get(30, TimeUnit.SECONDS);
            paymentIds.add(r.payment().id());
            created += r.created() ? 1 : 0;
        }
        assertThat(paymentIds).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(paymentRepository.countByBookingId(booking.id())).isEqualTo(1);
        assertThat(reload(booking.id()).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void concurrentRequestsWithDifferentKeysChargeAtMostOnce() throws Exception {
        BookingResponse booking = flatBooking(10, 2);
        List<Future<PaymentService.PayResult>> results = race(12, () ->
                paymentService.pay(user.getId(), booking.id(), key(), new PaymentRequest("tok_success")));

        int succeeded = 0;
        for (Future<PaymentService.PayResult> result : results) {
            try {
                result.get(30, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ConflictException.class); // in progress / already paid
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(paymentRepository.findByBookingIdIn(List.of(booking.id())))
                .filteredOn(p -> p.getStatus() == PaymentStatus.SUCCESS).hasSize(1);
    }

    private <T> List<Future<T>> race(int threads, java.util.concurrent.Callable<T> task) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        return futures;
    }
}
