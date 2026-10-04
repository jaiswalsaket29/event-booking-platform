package org.saket.eventbooking.payment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The default runtime mode end to end: the charge answers PENDING, then the simulated provider's signed
 * webhook (through the real signature check) settles the payment and the booking. Separate context.
 */
@TestPropertySource(properties = {
        "app.payments.simulated.mode=WEBHOOK",
        "app.payments.simulated.webhook-delay=200ms"})
class WebhookModeCheckoutTest extends IntegrationTest {

    @Autowired BookingService bookingService;

    private String waitForBookingStatus(User user, UUID bookingId, String expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String status;
        do {
            Thread.sleep(100);
            String body = mockMvc.perform(get("/api/v1/bookings/{id}", bookingId).header("Authorization", bearer(user)))
                    .andReturn().getResponse().getContentAsString();
            status = JsonPath.read(body, "$.status");
        } while (!status.equals(expected) && Instant.now().isBefore(deadline));
        return status;
    }

    @Test
    void checkoutIsSettledByTheWebhook() throws Exception {
        User user = createUser(Role.USER, true);
        UUID eventId = fixtures.publishedEvent(unique("Async Pay")).id();
        SessionResponse session = fixtures.flatSession(eventId, fixtures.location(unique("Latency")).id(),
                daysFromNow(5), 10, "500");
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, 1, null));

        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.id())
                        .header("Authorization", bearer(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\": \"tok_success\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bookingStatus").value("PENDING"));

        assertThat(waitForBookingStatus(user, booking.id(), "CONFIRMED")).isEqualTo("CONFIRMED");
        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.id()).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].transactionId").exists());
    }

    @Test
    void declineArrivesByWebhookToo() throws Exception {
        User user = createUser(Role.USER, true);
        UUID eventId = fixtures.publishedEvent(unique("Async Decline")).id();
        SessionResponse session = fixtures.flatSession(eventId, fixtures.location(unique("Latency")).id(),
                daysFromNow(5), 10, "500");
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, 1, null));

        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.id())
                        .header("Authorization", bearer(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\": \"tok_decline\"}"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String paymentStatus;
        do {
            Thread.sleep(100);
            String body = mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.id())
                    .header("Authorization", bearer(user))).andReturn().getResponse().getContentAsString();
            paymentStatus = JsonPath.read(body, "$[0].status");
        } while (paymentStatus.equals("PENDING") && Instant.now().isBefore(deadline));
        assertThat(paymentStatus).isEqualTo("FAILED");
        assertThat(waitForBookingStatus(user, booking.id(), "PENDING")).isEqualTo("PENDING");
    }
}
