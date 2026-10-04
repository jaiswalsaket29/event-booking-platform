package org.saket.eventbooking.booking;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MyBookingsTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingExpiryService expiryService;
    @Autowired SessionService sessionService;

    @Test
    void usersSeeOnlyTheirOwnBookingsNewestFirst() throws Exception {
        User alice = createUser(Role.USER, true);
        User bob = createUser(Role.USER, true);
        UUID eventId = fixtures.publishedEvent(unique("Mine")).id();
        LocationResponse venue = fixtures.location(unique("Ownership"));
        HallDetailResponse hall = fixtures.hall(venue.id(), 1, 3);
        SessionResponse flat = fixtures.flatSession(eventId, venue.id(), daysFromNow(6), 50, "250");
        SessionResponse seated = fixtures.assignedSession(eventId, venue.id(), hall.id(), daysFromNow(7), "300");
        List<UUID> seats = sessionService.seatMap(seated.id()).seats().stream().limit(2).map(s -> s.id()).toList();

        BookingResponse older = bookingService.create(alice.getId(), new CreateBookingRequest(flat.id(), null, 2, null));
        expiryService.expire(older.id());
        BookingResponse newer = bookingService.create(alice.getId(), new CreateBookingRequest(seated.id(), null, null, seats));
        BookingResponse bobs = bookingService.create(bob.getId(), new CreateBookingRequest(flat.id(), null, 1, null));

        mockMvc.perform(get("/api/v1/bookings").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(newer.id().toString()))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].seats", hasSize(2)))
                .andExpect(jsonPath("$.content[1].id").value(older.id().toString()))
                .andExpect(jsonPath("$.content[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.content[1].holdExpiresAt").doesNotExist());

        mockMvc.perform(get("/api/v1/bookings/{id}", newer.id()).header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.seatingType").value("ASSIGNED_SEATING"))
                .andExpect(jsonPath("$.ticketCount").value(2));

        // Alice can't read Bob's booking, and can't tell whether it exists
        mockMvc.perform(get("/api/v1/bookings/{id}", bobs.id()).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}", UUID.randomUUID()).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void bookingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
