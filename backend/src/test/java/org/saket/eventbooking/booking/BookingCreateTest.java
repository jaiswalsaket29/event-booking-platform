package org.saket.eventbooking.booking;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.hold.BookingHoldStore;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SeatMapResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingCreateTest extends IntegrationTest {

    @Autowired SessionService sessionService;
    @Autowired BookingHoldStore holdStore;

    private String user;
    private UUID eventId;
    private LocationResponse venue;
    private HallDetailResponse hall; // 1 REGULAR row + 1 RECLINER row, 4 seats each

    @BeforeEach
    void setUp() {
        user = userToken();
        eventId = fixtures.publishedEvent(unique("Bookable")).id();
        venue = fixtures.location(unique("Ticketville"));
        hall = fixtures.hall(venue.id(), 1, 4);
    }

    private ResultActions book(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void flatBookingHoldsCapacityAndStartsTheTimer() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), 10, "450");

        String body = book(user, "{\"sessionId\": \"%s\", \"quantity\": 3}".formatted(session.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bookingReference").doesNotExist())
                .andExpect(jsonPath("$.ticketCount").value(3))
                .andExpect(jsonPath("$.totalAmount").value(1350))
                .andExpect(jsonPath("$.city").value(venue.city()))
                .andExpect(jsonPath("$.seats", hasSize(0)))
                .andReturn().getResponse().getContentAsString();

        UUID bookingId = UUID.fromString(JsonPath.read(body, "$.id"));
        Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.holdExpiresAt"));
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(10));
        assertThat(holdStore.remainingTtl(bookingId)).hasValueSatisfying(ttl ->
                assertThat(ttl).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10)));
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(7);
    }

    @Test
    void tieredBookingUsesTheChosenTier() throws Exception {
        SessionResponse session = fixtures.tieredSession(eventId, venue.id(), daysFromNow(5), List.of(
                new TicketTierRequest("Standard", new BigDecimal("800"), 100),
                new TicketTierRequest("VIP", new BigDecimal("2500"), 10)));
        UUID vip = session.tiers().stream().filter(t -> t.name().equals("VIP")).findFirst().orElseThrow().id();

        book(user, "{\"sessionId\": \"%s\", \"ticketTierId\": \"%s\", \"quantity\": 2}".formatted(session.id(), vip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketTier.name").value("VIP"))
                .andExpect(jsonPath("$.totalAmount").value(5000));
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(108);
    }

    @Test
    void assignedBookingLocksTheSeatsAndListsThem() throws Exception {
        SessionResponse session = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(5), "300");
        SeatMapResponse map = sessionService.seatMap(session.id());
        UUID b1 = map.seats().get(1).id();
        UUID a2 = map.seats().get(5).id(); // recliner row

        book(user, "{\"sessionId\": \"%s\", \"sessionSeatIds\": [\"%s\", \"%s\"]}".formatted(session.id(), a2, b1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketCount").value(2))
                .andExpect(jsonPath("$.hallName").value(hall.name()))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(2))
                .andExpect(jsonPath("$.seats[1].seatType").value("RECLINER"))
                .andExpect(jsonPath("$.totalAmount").value(900));

        SeatMapResponse after = sessionService.seatMap(session.id());
        assertThat(after.seats().get(1).status()).isEqualTo(SessionSeatStatus.LOCKED);
        assertThat(after.seats().get(5).status()).isEqualTo(SessionSeatStatus.LOCKED);

        // someone else trying for one of those seats
        book(userToken(), "{\"sessionId\": \"%s\", \"sessionSeatIds\": [\"%s\"]}".formatted(session.id(), b1))
                .andExpect(status().isConflict());
    }

    @Test
    void unverifiedUsersCannotBook() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), 10, "450");
        book(bearer(createUser(Role.USER, false)), "{\"sessionId\": \"%s\", \"quantity\": 1}".formatted(session.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Verify your email address before booking tickets"));
        mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"%s\", \"quantity\": 1}".formatted(session.id())))
                .andExpect(status().isUnauthorized());
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(10);
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), 20, "450");
        book(user, "{\"quantity\": 1}").andExpect(status().isBadRequest());
        book(user, "{\"sessionId\": \"%s\", \"quantity\": 0}".formatted(session.id())).andExpect(status().isBadRequest());
        book(user, "{\"sessionId\": \"%s\", \"quantity\": 11}".formatted(session.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("At most 10 tickets per booking"));
        book(user, "{\"sessionId\": \"%s\", \"ticketTierId\": \"%s\", \"quantity\": 1}".formatted(session.id(), UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        book(user, "{\"sessionId\": \"%s\", \"quantity\": 1}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
        assertThat(sessionService.get(session.id()).ticketsAvailable()).isEqualTo(20);
    }

    @Test
    void soldOutIsAConflict() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, venue.id(), daysFromNow(5), 2, "450");
        book(user, "{\"sessionId\": \"%s\", \"quantity\": 2}".formatted(session.id())).andExpect(status().isCreated());
        book(user, "{\"sessionId\": \"%s\", \"quantity\": 1}".formatted(session.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Sold out"));
    }
}
