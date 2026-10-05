package org.saket.eventbooking.booking;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.dto.DashboardStatsResponse;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.booking.service.BookingStatsService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.service.PaymentAttemptService;
import org.saket.eventbooking.payment.service.PaymentService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminBookingsTest extends IntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired BookingService bookingService;
    @Autowired BookingExpiryService expiryService;
    @Autowired BookingStatsService statsService;
    @Autowired BookingRepository bookingRepository;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAttemptService attemptService;
    @Autowired SessionService sessionService;
    @Autowired org.saket.eventbooking.payment.repository.PaymentRepository paymentRepository;

    private String admin;
    private User alice;
    private User bob;
    private UUID bigEventId;
    private UUID seatedEventId;
    private LocationResponse venue;

    @BeforeEach
    void setUp() {
        admin = adminToken();
        alice = createUser(Role.USER, true);
        bob = createUser(Role.USER, true);
        bigEventId = fixtures.publishedEvent(unique("Stadium Night")).id();
        seatedEventId = fixtures.publishedEvent(unique("Chamber Recital")).id();
        venue = fixtures.location(unique("Ledger"));
    }

    private BookingResponse paidFlat(User user, UUID eventId, String price, int quantity) {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(8), 100, price);
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, quantity, null));
        paymentService.pay(user.getId(), booking.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_success"));
        return booking;
    }

    private BookingResponse paidSeats(User user) {
        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 3);
        SessionResponse session = fixtures.assignedSession(seatedEventId, venue.id(), hall.id(), daysFromNow(8), "300");
        List<UUID> seats = sessionService.seatMap(session.id()).seats().stream().limit(2).map(s -> s.id()).toList();
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, null, seats));
        paymentService.pay(user.getId(), booking.id(), UUID.randomUUID().toString(), new PaymentRequest("tok_success"));
        return booking;
    }

    private DashboardStatsResponse.Day today(DashboardStatsResponse stats) {
        LocalDate today = LocalDate.now(IST);
        return stats.daily().stream().filter(d -> d.date().equals(today)).findFirst().orElseThrow();
    }

    @Test
    void adminCanFilterBookingsAndSeeCustomersAndPayments() throws Exception {
        BookingResponse confirmed = paidFlat(alice, bigEventId, "500", 2);
        BookingResponse pending = bookingService.create(bob.getId(), new CreateBookingRequest(
                fixtures.flatSession(bigEventId, venue.id(), daysFromNow(9), 10, "500").id(), null, 1, null));
        String reference = bookingRepository.findById(confirmed.id()).orElseThrow().getBookingReference();

        mockMvc.perform(get("/api/v1/admin/bookings").header("Authorization", admin)
                        .param("eventId", bigEventId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].booking.id").value(pending.id().toString())) // newest first
                .andExpect(jsonPath("$.content[1].customer.email").value(alice.getEmail()))
                .andExpect(jsonPath("$.content[1].payments", hasSize(1)))
                .andExpect(jsonPath("$.content[1].payments[0].status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/admin/bookings").header("Authorization", admin)
                        .param("eventId", bigEventId.toString()).param("status", "PENDING"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customer.email").value(bob.getEmail()));

        mockMvc.perform(get("/api/v1/admin/bookings").header("Authorization", admin).param("q", reference.toLowerCase()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].booking.bookingReference").value(reference));

        String emailPart = alice.getEmail().substring(5, 20).toUpperCase();
        mockMvc.perform(get("/api/v1/admin/bookings").header("Authorization", admin).param("q", emailPart))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].booking.id").value(confirmed.id().toString()));

        mockMvc.perform(get("/api/v1/admin/bookings/{id}", confirmed.id()).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.customer.id").value(alice.getId().toString()));
        mockMvc.perform(get("/api/v1/admin/bookings/{id}", UUID.randomUUID()).header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void dashboardStatsCountConfirmedBookingsTicketsAndRevenue() throws Exception {
        DashboardStatsResponse before = statsService.stats(null, null);

        paidFlat(alice, bigEventId, "90000", 2);   // 180,000 over 2 tickets: biggest event in the test DB
        paidSeats(bob);                              // 2 seats x 300 = 600
        bookingService.create(alice.getId(), new CreateBookingRequest(                    // pending: not counted
                fixtures.flatSession(bigEventId, venue.id(), daysFromNow(9), 10, "500").id(), null, 1, null));

        String json = mockMvc.perform(get("/api/v1/admin/dashboard/stats").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.daily", hasSize(30)))
                .andExpect(jsonPath("$.topEvents[0].eventId").value(bigEventId.toString()))
                .andExpect(jsonPath("$.topEvents[0].revenue").value(180000))
                .andExpect(jsonPath("$.topEvents[0].ticketsSold").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(json, "$.topEvents[*].eventId")).contains(seatedEventId.toString());

        DashboardStatsResponse after = statsService.stats(null, null);
        assertThat(after.totals().confirmedBookings() - before.totals().confirmedBookings()).isEqualTo(2);
        assertThat(after.totals().ticketsSold() - before.totals().ticketsSold()).isEqualTo(4);
        assertThat(after.totals().revenue().subtract(before.totals().revenue())).isEqualByComparingTo("180600");
        assertThat(today(after).confirmedBookings() - today(before).confirmedBookings()).isEqualTo(2);
        assertThat(today(after).revenue().subtract(today(before).revenue())).isEqualByComparingTo("180600");
    }

    @Test
    void latePaymentsAreSurfacedForRefund() {
        long before = statsService.stats(null, null).totals().latePaymentsNeedingRefund();

        SessionResponse session = fixtures.flatSession(bigEventId, venue.id(), daysFromNow(8), 10, "100");
        BookingResponse booking = bookingService.create(alice.getId(), new CreateBookingRequest(session.id(), null, 1, null));
        PaymentAttemptService.OpenedAttempt attempt = attemptService.open(alice.getId(), booking.id(), UUID.randomUUID().toString());
        // the payment got no outcome for longer than app.booking.payment-grace, so expiry goes ahead
        var payment = paymentRepository.findById(attempt.paymentId()).orElseThrow();
        payment.setCreatedAt(java.time.Instant.now().minus(java.time.Duration.ofMinutes(5)));
        paymentRepository.save(payment);
        expiryService.expire(booking.id());
        attemptService.applyOutcome(attempt.paymentId(), "txn_" + UUID.randomUUID(), ChargeResult.Status.SUCCEEDED,
                null, new BigDecimal("100.00"));

        assertThat(statsService.stats(null, null).totals().latePaymentsNeedingRefund()).isEqualTo(before + 1);
    }

    @Test
    void customRangesAreZeroFilledAndValidated() throws Exception {
        LocalDate end = LocalDate.now(IST);
        mockMvc.perform(get("/api/v1/admin/dashboard/stats").header("Authorization", admin)
                        .param("from", end.minusDays(6).toString()).param("to", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily", hasSize(7)))
                .andExpect(jsonPath("$.daily[0].date").value(end.minusDays(6).toString()));
        mockMvc.perform(get("/api/v1/admin/dashboard/stats").header("Authorization", admin)
                        .param("from", "2026-01-10").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/dashboard/stats").header("Authorization", admin)
                        .param("from", "2024-01-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }
}
