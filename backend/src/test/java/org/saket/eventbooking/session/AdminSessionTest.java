package org.saket.eventbooking.session;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSessionTest extends IntegrationTest {

    private String admin;
    private UUID eventId;
    private LocationResponse location;
    private HallDetailResponse hall; // 2 rows x 3 REGULAR + 1 row x 3 RECLINER = 9 seats
    private final Instant start = daysFromNow(10).truncatedTo(ChronoUnit.SECONDS);
    private final Instant end = start.plus(2, ChronoUnit.HOURS);

    @BeforeEach
    void setUp() {
        admin = adminToken();
        eventId = fixtures.publishedEvent(unique("Session Test Event")).id();
        location = fixtures.location(unique("Portside"));
        hall = fixtures.hall(location.id(), 2, 3);
    }

    private ResultActions createSession(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/events/{id}/sessions", eventId)
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String body(String seating, String extra) {
        return """
                {"locationId": "%s", "startTime": "%s", "endTime": "%s", "seatingType": "%s"%s}
                """.formatted(location.id(), start, end, seating, extra);
    }

    @Test
    void assignedSeatingGeneratesSessionSeatsWithPriceSnapshots() throws Exception {
        String response = createSession(body("ASSIGNED_SEATING",
                ", \"hallId\": \"%s\", \"basePrice\": 200.00".formatted(hall.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatingType").value("ASSIGNED_SEATING"))
                .andExpect(jsonPath("$.pricingMode").doesNotExist())
                .andExpect(jsonPath("$.hallId").value(hall.id().toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.ticketsAvailable").value(9))
                .andExpect(jsonPath("$.location.city").value(location.city()))
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(response, "$.id");

        mockMvc.perform(get("/api/v1/admin/sessions/{id}/seats", sessionId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(9)))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[0].price").value(200.00))
                .andExpect(jsonPath("$.seats[8].rowLabel").value("C"))
                .andExpect(jsonPath("$.seats[8].seatType").value("RECLINER"))
                .andExpect(jsonPath("$.seats[8].price").value(400.00));

        // the hall's layout is now frozen, and the hall can't be deleted
        mockMvc.perform(put("/api/v1/admin/halls/{id}/seats", hall.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\": [{\"rows\": 1, \"seatsPerRow\": 1, \"seatType\": \"REGULAR\"}]}"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/admin/halls/{id}", hall.id()).header("Authorization", admin))
                .andExpect(status().isConflict());
        // and neither can the event
        mockMvc.perform(delete("/api/v1/admin/events/{id}", eventId).header("Authorization", admin))
                .andExpect(status().isConflict());

        // deleting the session (no bookings) releases the hall again
        mockMvc.perform(delete("/api/v1/admin/sessions/{id}", sessionId).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/admin/halls/{id}/seats", hall.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\": [{\"rows\": 1, \"seatsPerRow\": 1, \"seatType\": \"REGULAR\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void flatGeneralAdmission() throws Exception {
        createSession(body("GENERAL_ADMISSION",
                ", \"pricingMode\": \"FLAT\", \"availableCapacity\": 500, \"basePrice\": 999"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pricingMode").value("FLAT"))
                .andExpect(jsonPath("$.hallId").doesNotExist())
                .andExpect(jsonPath("$.ticketsAvailable").value(500))
                .andExpect(jsonPath("$.basePrice").value(999))
                .andExpect(jsonPath("$.tiers", hasSize(0)));
    }

    @Test
    void tieredGeneralAdmissionUsesCheapestTierAsBasePrice() throws Exception {
        createSession(body("GENERAL_ADMISSION", """
                , "pricingMode": "TIERED", "tiers": [
                  {"name": "VIP", "price": 4999, "totalCapacity": 50},
                  {"name": "Standard", "price": 1499, "totalCapacity": 400}
                ]"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.basePrice").value(1499))
                .andExpect(jsonPath("$.ticketsAvailable").value(450))
                .andExpect(jsonPath("$.tiers", hasSize(2)))
                .andExpect(jsonPath("$.tiers[0].name").value("Standard"))
                .andExpect(jsonPath("$.tiers[0].availableCapacity").value(400));
    }

    @Test
    void seatingAndPricingModesCannotBeMixed() throws Exception {
        String hallId = hall.id().toString();
        List<String> invalid = List.of(
                // assigned seating
                body("ASSIGNED_SEATING", ", \"basePrice\": 100"),
                body("ASSIGNED_SEATING", ", \"hallId\": \"%s\"".formatted(hallId)),
                body("ASSIGNED_SEATING", ", \"hallId\": \"%s\", \"basePrice\": 100, \"pricingMode\": \"FLAT\"".formatted(hallId)),
                body("ASSIGNED_SEATING", ", \"hallId\": \"%s\", \"basePrice\": 100, \"availableCapacity\": 10".formatted(hallId)),
                body("ASSIGNED_SEATING", ", \"hallId\": \"%s\", \"basePrice\": 100, \"tiers\": [{\"name\": \"A\", \"price\": 1, \"totalCapacity\": 1}]".formatted(hallId)),
                // general admission
                body("GENERAL_ADMISSION", ", \"availableCapacity\": 10, \"basePrice\": 100"),
                body("GENERAL_ADMISSION", ", \"hallId\": \"%s\", \"pricingMode\": \"FLAT\", \"availableCapacity\": 10, \"basePrice\": 100".formatted(hallId)),
                body("GENERAL_ADMISSION", ", \"pricingMode\": \"FLAT\", \"basePrice\": 100"),
                body("GENERAL_ADMISSION", ", \"pricingMode\": \"FLAT\", \"availableCapacity\": 10"),
                body("GENERAL_ADMISSION", ", \"pricingMode\": \"FLAT\", \"availableCapacity\": 10, \"basePrice\": 100, \"tiers\": [{\"name\": \"A\", \"price\": 1, \"totalCapacity\": 1}]"),
                body("GENERAL_ADMISSION", ", \"pricingMode\": \"TIERED\""),
                body("GENERAL_ADMISSION", ", \"pricingMode\": \"TIERED\", \"availableCapacity\": 10, \"tiers\": [{\"name\": \"A\", \"price\": 1, \"totalCapacity\": 1}]"),
                body("GENERAL_ADMISSION", ", \"pricingMode\": \"TIERED\", \"tiers\": [{\"name\": \"VIP\", \"price\": 1, \"totalCapacity\": 1}, {\"name\": \"vip\", \"price\": 2, \"totalCapacity\": 1}]"));
        for (String json : invalid) {
            createSession(json).andExpect(status().isBadRequest());
        }
    }

    @Test
    void hallMustBelongToTheLocationAndHaveSeats() throws Exception {
        LocationResponse other = fixtures.location(unique("Elsewhere"));
        HallDetailResponse otherHall = fixtures.hall(other.id(), 1, 1);
        createSession(body("ASSIGNED_SEATING", ", \"hallId\": \"%s\", \"basePrice\": 100".formatted(otherHall.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("location")));

        String emptyHall = JsonPath.read(mockMvc.perform(post("/api/v1/admin/locations/{id}/halls", location.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Empty\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        createSession(body("ASSIGNED_SEATING", ", \"hallId\": \"%s\", \"basePrice\": 100".formatted(emptyHall)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("no seats")));
    }

    @Test
    void timesAreValidated() throws Exception {
        createSession("""
                {"locationId": "%s", "startTime": "%s", "endTime": "%s", "seatingType": "GENERAL_ADMISSION",
                 "pricingMode": "FLAT", "availableCapacity": 10, "basePrice": 100}
                """.formatted(location.id(), start, start.minusSeconds(60)))
                .andExpect(status().isBadRequest());
        createSession("""
                {"locationId": "%s", "startTime": "%s", "endTime": "%s", "seatingType": "GENERAL_ADMISSION",
                 "pricingMode": "FLAT", "availableCapacity": 10, "basePrice": 100}
                """.formatted(location.id(), Instant.now().minusSeconds(3600), Instant.now()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startTime"));
    }

    @Test
    void generalAdmissionHasNoSeatMap() throws Exception {
        SessionResponse ga = fixtures.flatSession(eventId, location.id(), start, 100, "500");
        mockMvc.perform(get("/api/v1/admin/sessions/{id}/seats", ga.id()).header("Authorization", admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tierManagement() throws Exception {
        SessionResponse session = fixtures.tieredSession(eventId, location.id(), start, List.of(
                new TicketTierRequest("Standard", new BigDecimal("1000"), 100)));
        UUID standardId = session.tiers().getFirst().id();

        mockMvc.perform(post("/api/v1/admin/sessions/{id}/tiers", session.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Early Bird\", \"price\": 700, \"totalCapacity\": 20}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tiers", hasSize(2)))
                .andExpect(jsonPath("$.basePrice").value(700))
                .andExpect(jsonPath("$.ticketsAvailable").value(120));

        mockMvc.perform(put("/api/v1/admin/tiers/{id}", standardId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Standard\", \"price\": 1200, \"totalCapacity\": 150}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketsAvailable").value(170));

        // duplicate name
        mockMvc.perform(put("/api/v1/admin/tiers/{id}", standardId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"early bird\", \"price\": 1200, \"totalCapacity\": 150}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/admin/tiers/{id}", standardId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers", hasSize(1)));

        String lastTierId = JsonPath.read(mockMvc.perform(get("/api/v1/admin/sessions/{id}", session.id())
                .header("Authorization", admin)).andReturn().getResponse().getContentAsString(), "$.tiers[0].id");
        mockMvc.perform(delete("/api/v1/admin/tiers/{id}", lastTierId).header("Authorization", admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tiersCannotBeAddedToNonTieredSessions() throws Exception {
        SessionResponse flat = fixtures.flatSession(eventId, location.id(), start, 100, "500");
        mockMvc.perform(post("/api/v1/admin/sessions/{id}/tiers", flat.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"VIP\", \"price\": 700, \"totalCapacity\": 20}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sessionUpdateAndCancellation() throws Exception {
        SessionResponse session = fixtures.flatSession(eventId, location.id(), start, 100, "500");
        Instant newStart = start.plus(1, ChronoUnit.DAYS);

        mockMvc.perform(put("/api/v1/admin/sessions/{id}", session.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startTime": "%s", "endTime": "%s", "status": "CANCELLED"}
                                """.formatted(newStart, newStart.plus(2, ChronoUnit.HOURS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.startTime").value(newStart.toString()));

        mockMvc.perform(put("/api/v1/admin/sessions/{id}", session.id())
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startTime": "%s", "endTime": "%s", "status": "SCHEDULED"}
                                """.formatted(newStart, newStart.plus(2, ChronoUnit.HOURS))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/events/{id}/sessions", eventId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void cancelledEventsCannotGetNewSessions() throws Exception {
        UUID cancelled = fixtures.event(unique("Called Off"), "Comedy",
                org.saket.eventbooking.event.enums.EventStatus.CANCELLED).id();
        mockMvc.perform(post("/api/v1/admin/events/{id}/sessions", cancelled)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("GENERAL_ADMISSION", ", \"pricingMode\": \"FLAT\", \"availableCapacity\": 10, \"basePrice\": 100")))
                .andExpect(status().isBadRequest());
    }
}
