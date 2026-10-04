package org.saket.eventbooking.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.event.dto.ArtistRequest;
import org.saket.eventbooking.event.dto.EventArtistsRequest;
import org.saket.eventbooking.event.dto.EventImageRequest;
import org.saket.eventbooking.event.dto.EventResponse;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.event.service.ArtistService;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.SessionUpdateRequest;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public catalog reads, all anonymous. Each test run uses its own city so data created by
 * other test classes in the shared database doesn't affect the assertions.
 */
class PublicCatalogTest extends IntegrationTest {

    @Autowired
    EventService eventService;
    @Autowired
    ArtistService artistService;
    @Autowired
    SessionService sessionService;

    private String city;
    private EventResponse music;    // assigned seating in 5 days, base 300
    private EventResponse comedy;   // flat GA in 2 and 20 days, 500
    private EventResponse festival; // tiered GA in 8 days, from 700; featured
    private EventResponse draft;    // has a session, but not published
    private SessionResponse musicSession;
    private SessionResponse comedyCancelled;
    private UUID headlinerId;

    @BeforeEach
    void setUp() {
        city = unique("Testopolis");
        LocationResponse venue = fixtures.location(city);
        HallDetailResponse hall = fixtures.hall(venue.id(), 2, 4);

        music = fixtures.publishedEvent(unique("Zephyr Live"));
        comedy = fixtures.event(unique("Laugh Track"), "Comedy", EventStatus.PUBLISHED);
        festival = eventService.create(new org.saket.eventbooking.event.dto.EventRequest(unique("Allnighter Fest"),
                "Big one", "Festival", null, EventStatus.PUBLISHED, "English", 600, "18+", null, null, true));
        draft = fixtures.event(unique("Secret Show"), "Music", EventStatus.DRAFT);
        EventResponse pastOnly = fixtures.publishedEvent(unique("Yesterday"));

        musicSession = fixtures.assignedSession(music.id(), venue.id(), hall.id(), daysFromNow(5), "300");
        fixtures.flatSession(comedy.id(), venue.id(), daysFromNow(2), 100, "500");
        fixtures.flatSession(comedy.id(), venue.id(), daysFromNow(20), 100, "500");
        comedyCancelled = fixtures.flatSession(comedy.id(), venue.id(), daysFromNow(1), 100, "200");
        Instant cancelledStart = comedyCancelled.startTime();
        sessionService.update(comedyCancelled.id(), new SessionUpdateRequest(
                cancelledStart, cancelledStart.plus(Duration.ofHours(1)), SessionStatus.CANCELLED));
        fixtures.tieredSession(festival.id(), venue.id(), daysFromNow(8), List.of(
                new TicketTierRequest("GA", new BigDecimal("700"), 1000),
                new TicketTierRequest("VIP", new BigDecimal("2500"), 100)));
        fixtures.flatSession(draft.id(), venue.id(), daysFromNow(3), 100, "100");
        fixtures.flatSession(pastOnly.id(), venue.id(), Instant.now().minus(Duration.ofDays(1)), 100, "100");

        headlinerId = artistService.create(new ArtistRequest(unique("Aurora Vale"), "Singer", null, "Pop")).id();
        eventService.replaceArtists(music.id(), new EventArtistsRequest(
                List.of(new EventArtistsRequest.Entry(headlinerId, "headliner"))));
        eventService.addImage(music.id(), new EventImageRequest("https://img.example.test/zephyr-1.jpg", 0));
    }

    @Test
    void listsPublishedEventsWithUpcomingSessionsSoonestFirst() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("city", city.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content", hasSize(3)))
                // comedy's cancelled session (1 day) is ignored, so its next session is in 2 days
                .andExpect(jsonPath("$.content[0].id").value(comedy.id().toString()))
                .andExpect(jsonPath("$.content[0].startingPrice").value(500))
                .andExpect(jsonPath("$.content[1].id").value(music.id().toString()))
                .andExpect(jsonPath("$.content[2].id").value(festival.id().toString()))
                .andExpect(jsonPath("$.content[2].startingPrice").value(700));
    }

    @Test
    void filtersByCategoryDateRangeTextAndFeatured() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("city", city).param("category", "comedy"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(comedy.id().toString()));

        mockMvc.perform(get("/api/v1/events").param("city", city)
                        .param("from", daysFromNow(4).toString())
                        .param("to", daysFromNow(9).toString()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(music.id().toString()))
                .andExpect(jsonPath("$.content[1].id").value(festival.id().toString()));

        // within a date range, nextSessionStart is the first session inside the range
        mockMvc.perform(get("/api/v1/events").param("city", city).param("category", "Comedy")
                        .param("from", daysFromNow(10).toString()))
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(get("/api/v1/events").param("city", city).param("q", "laugh"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(comedy.id().toString()));

        mockMvc.perform(get("/api/v1/events").param("city", city).param("featured", "true"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(festival.id().toString()));

        mockMvc.perform(get("/api/v1/events").param("city", city).param("artistId", headlinerId.toString()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(music.id().toString()));
    }

    @Test
    void sortsByTitleAndPages() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("city", city).param("sort", "TITLE")
                        .param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(festival.id().toString())) // "Allnighter..."
                .andExpect(jsonPath("$.content[1].id").value(comedy.id().toString()))   // "Laugh..."
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/events").param("city", city).param("sort", "TITLE")
                        .param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(music.id().toString()));   // "Zephyr..."
    }

    @Test
    void rejectsBadListParameters() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("size", "500")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/events").param("sort", "PRICE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/events")
                        .param("from", daysFromNow(5).toString()).param("to", daysFromNow(1).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventDetailHasLineUpGalleryAndUpcomingSessions() throws Exception {
        mockMvc.perform(get("/api/v1/events/{id}", music.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.title").value(music.title()))
                .andExpect(jsonPath("$.artists", hasSize(1)))
                .andExpect(jsonPath("$.artists[0].role").value("headliner"))
                .andExpect(jsonPath("$.images", hasSize(1)))
                .andExpect(jsonPath("$.sessions", hasSize(1)))
                .andExpect(jsonPath("$.sessions[0].seatingType").value("ASSIGNED_SEATING"))
                .andExpect(jsonPath("$.sessions[0].ticketsAvailable").value(12));

        // cancelled sessions are not offered
        mockMvc.perform(get("/api/v1/events/{id}/sessions", comedy.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void unpublishedEventsAndTheirSessionsAreHidden() throws Exception {
        mockMvc.perform(get("/api/v1/events/{id}", draft.id())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/events/{id}/sessions", draft.id())).andExpect(status().isNotFound());
        UUID draftSession = sessionService.listForEvent(draft.id()).getFirst().id();
        mockMvc.perform(get("/api/v1/sessions/{id}", draftSession)).andExpect(status().isNotFound());
    }

    @Test
    void seatMapShowsStatusAndPricePerSeat() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}", musicSession.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(300));
        mockMvc.perform(get("/api/v1/sessions/{id}/seats", musicSession.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(12)))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[0].price").value(300))
                .andExpect(jsonPath("$.seats[11].seatType").value("RECLINER"))
                .andExpect(jsonPath("$.seats[11].price").value(600));
        mockMvc.perform(get("/api/v1/sessions/{id}/seats", comedyCancelled.id()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void artistPageListsUpcomingEvents() throws Exception {
        mockMvc.perform(get("/api/v1/artists/{id}", headlinerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artist.genre").value("Pop"))
                .andExpect(jsonPath("$.upcomingEvents", hasSize(1)))
                .andExpect(jsonPath("$.upcomingEvents[0].id").value(music.id().toString()));
        mockMvc.perform(get("/api/v1/artists").param("q", "aurora vale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(headlinerId.toString())));
    }

    @Test
    void categoriesAndLocationsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/events/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("Comedy")))
                .andExpect(jsonPath("$", hasItem("Festival")));
        mockMvc.perform(get("/api/v1/locations").param("city", city))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
