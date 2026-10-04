package org.saket.eventbooking.common.seed;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.event.service.ArtistService;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.location.service.LocationService;
import org.saket.eventbooking.session.service.SessionService;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seeder is disabled in the test profile; here it's built by hand and run against the shared
 * database. Its city names are distinct from the unique cities other tests use.
 */
class DemoDataSeederTest extends IntegrationTest {

    @Autowired LocationService locationService;
    @Autowired HallService hallService;
    @Autowired ArtistService artistService;
    @Autowired EventService eventService;
    @Autowired SessionService sessionService;
    @Autowired ApplicationContext context;

    @Test
    void seedsAPublicCatalogCoveringAllSeatingModes() throws Exception {
        assertThat(context.getBeanNamesForType(DemoDataSeeder.class)).as("disabled in tests").isEmpty();

        new DemoDataSeeder(locationService, hallService, artistService, eventService, sessionService).seed();

        mockMvc.perform(get("/api/v1/locations/cities"))
                .andExpect(jsonPath("$", hasItems("Mumbai", "Bengaluru", "Pune", "Delhi", "Hyderabad")));
        mockMvc.perform(get("/api/v1/events").param("city", "Pune").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", hasItems("Orbit of Echoes", "Pixel Arcade Weekend Pass")));
        mockMvc.perform(get("/api/v1/events").param("city", "Bengaluru").param("featured", "true").param("size", "50"))
                .andExpect(jsonPath("$.content[*].title", hasItem("Solace Sessions: Open Air")));
        mockMvc.perform(get("/api/v1/events").param("q", "Winter Lights"))
                .andExpect(jsonPath("$.totalElements").value(0)); // the draft stays hidden

        String delhi = mockMvc.perform(get("/api/v1/events").param("city", "Delhi").param("q", "Last Lantern"))
                .andReturn().getResponse().getContentAsString();
        String eventId = com.jayway.jsonpath.JsonPath.read(delhi, "$.content[0].id");
        String detail = mockMvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(jsonPath("$.sessions[*].seatingType", everyItem(org.hamcrest.Matchers.is("ASSIGNED_SEATING"))))
                .andExpect(jsonPath("$.sessions[0].ticketsAvailable", greaterThan(200)))
                .andReturn().getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(detail, "$.sessions[0].id");
        mockMvc.perform(get("/api/v1/sessions/{id}/seats", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[*].seatType", hasItems("REGULAR", "PREMIUM")));

        mockMvc.perform(get("/api/v1/events").param("city", "Mumbai").param("q", "Tidal Tour"))
                .andExpect(jsonPath("$.content[0].startingPrice").value(999));
    }
}
