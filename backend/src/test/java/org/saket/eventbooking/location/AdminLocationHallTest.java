package org.saket.eventbooking.location;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminLocationHallTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void setUp() {
        admin = adminToken();
    }

    private String createLocation(String city, String venueType) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/locations")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Lakeside Arena", "address": "12 Shore Road", "city": "%s", "venueType": "%s"}
                                """.formatted(city, venueType)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value(city))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createHall(String locationId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/locations/{id}/halls", locationId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Screen 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCapacity").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void locationCrudRoundTrip() throws Exception {
        String city = unique("Rivertown");
        String id = createLocation(city, "OFFLINE");

        mockMvc.perform(put("/api/v1/admin/locations/{id}", id)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Lakeside Arena North", "city": "%s", "venueType": "OFFLINE"}
                                """.formatted(city)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lakeside Arena North"));

        // public reads
        mockMvc.perform(get("/api/v1/locations").param("city", city.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(get("/api/v1/locations/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem(city)));

        mockMvc.perform(delete("/api/v1/admin/locations/{id}", id).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/locations/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidLocationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/locations")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\", \"city\": \"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("venueType")));
    }

    @Test
    void bulkSeatLayoutGeneratesRowsAcrossBlocksAndSetsCapacity() throws Exception {
        String hallId = createHall(createLocation(unique("Hillview"), "OFFLINE"));

        mockMvc.perform(put("/api/v1/admin/halls/{id}/seats", hallId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"blocks": [
                                  {"rows": 2, "seatsPerRow": 5, "seatType": "REGULAR"},
                                  {"rows": 1, "seatsPerRow": 4, "seatType": "RECLINER"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCapacity").value(14))
                .andExpect(jsonPath("$.seats", hasSize(14)))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(1))
                .andExpect(jsonPath("$.seats[5].rowLabel").value("B"))
                .andExpect(jsonPath("$.seats[10].rowLabel").value("C"))
                .andExpect(jsonPath("$.seats[10].seatType").value("RECLINER"));

        // replacing the layout (no sessions yet) is allowed and recomputes capacity
        mockMvc.perform(put("/api/v1/admin/halls/{id}/seats", hallId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\": [{\"rows\": 1, \"seatsPerRow\": 3, \"seatType\": \"PREMIUM\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCapacity").value(3))
                .andExpect(jsonPath("$.seats", hasSize(3)));

        mockMvc.perform(get("/api/v1/admin/halls/{id}", hallId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(3)));
    }

    @Test
    void oversizedLayoutIsRejected() throws Exception {
        String hallId = createHall(createLocation(unique("Hillview"), "OFFLINE"));
        mockMvc.perform(put("/api/v1/admin/halls/{id}/seats", hallId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"blocks": [
                                  {"rows": 50, "seatsPerRow": 100, "seatType": "REGULAR"},
                                  {"rows": 1, "seatsPerRow": 1, "seatType": "REGULAR"}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlineLocationsCannotHaveHalls() throws Exception {
        String locationId = createLocation(unique("Webcast"), "ONLINE");
        mockMvc.perform(post("/api/v1/admin/locations/{id}/halls", locationId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Screen 1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void locationWithHallsCannotBeDeleted() throws Exception {
        String locationId = createLocation(unique("Hillview"), "OFFLINE");
        createHall(locationId);
        mockMvc.perform(delete("/api/v1/admin/locations/{id}", locationId).header("Authorization", admin))
                .andExpect(status().isConflict());
    }

    @Test
    void hallWithSeatsCanBeDeleted() throws Exception {
        String hallId = createHall(createLocation(unique("Hillview"), "OFFLINE"));
        mockMvc.perform(put("/api/v1/admin/halls/{id}/seats", hallId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\": [{\"rows\": 1, \"seatsPerRow\": 3, \"seatType\": \"REGULAR\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/halls/{id}", hallId).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin/halls/{id}", hallId).header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownHallReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/halls/{id}", "00000000-0000-0000-0000-000000000000")
                        .header("Authorization", admin))
                .andExpect(status().isNotFound());
    }
}
