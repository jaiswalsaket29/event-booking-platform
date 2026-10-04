package org.saket.eventbooking.event;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminEventArtistTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void setUp() {
        admin = adminToken();
    }

    private String createArtist(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/artists")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "bio": "Plays loud.", "genre": "Indie Rock"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createEvent(String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/events")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s", "category": "Music", "language": "English",
                                 "durationMinutes": 120, "featured": true}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.featured").value(true))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void artistCrudAndSearch() throws Exception {
        String name = unique("The Paper Kites Collective");
        String id = createArtist(name);

        mockMvc.perform(get("/api/v1/admin/artists").header("Authorization", admin)
                        .param("q", name.substring(name.length() - 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(put("/api/v1/admin/artists/{id}", id)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Renamed\", \"genre\": \"Folk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").value("Folk"));

        mockMvc.perform(delete("/api/v1/admin/artists/{id}", id).header("Authorization", admin))
                .andExpect(status().isNoContent());
    }

    @Test
    void eventLifecycleWithLineUpAndGallery() throws Exception {
        String title = unique("Monsoon Nights");
        String eventId = createEvent(title);
        String headliner = createArtist(unique("Neon Harbor"));
        String opener = createArtist(unique("Static Bloom"));

        mockMvc.perform(put("/api/v1/admin/events/{id}/artists", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"artists": [{"artistId": "%s", "role": "headliner"}, {"artistId": "%s", "role": "support"}]}
                                """.formatted(headliner, opener)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artists", hasSize(2)));

        String imageBody = mockMvc.perform(post("/api/v1/admin/events/{id}/images", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"https://img.example.test/b.jpg\", \"sortOrder\": 2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/v1/admin/events/{id}/images", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"https://img.example.test/a.jpg\", \"sortOrder\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.images", hasSize(2)))
                .andExpect(jsonPath("$.images[0].imageUrl").value("https://img.example.test/a.jpg"));
        String imageId = JsonPath.read(imageBody, "$.images[0].id");

        // artists in a line-up can't be deleted
        mockMvc.perform(delete("/api/v1/admin/artists/{id}", headliner).header("Authorization", admin))
                .andExpect(status().isConflict());

        // publish
        mockMvc.perform(put("/api/v1/admin/events/{id}", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s", "category": "Music", "status": "PUBLISHED", "featured": false}
                                """.formatted(title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.featured").value(false));

        mockMvc.perform(get("/api/v1/admin/events").header("Authorization", admin)
                        .param("status", "PUBLISHED").param("q", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(delete("/api/v1/admin/events/{id}/images/{imageId}", eventId, imageId)
                        .header("Authorization", admin))
                .andExpect(status().isNoContent());

        // clearing the line-up frees the artist
        mockMvc.perform(put("/api/v1/admin/events/{id}/artists", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artists\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artists", hasSize(0)));

        mockMvc.perform(get("/api/v1/admin/events/{id}", eventId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(1)))
                .andExpect(jsonPath("$.event.title").value(title));

        mockMvc.perform(delete("/api/v1/admin/events/{id}", eventId).header("Authorization", admin))
                .andExpect(status().isNoContent());
    }

    @Test
    void duplicateArtistInLineUpIsRejected() throws Exception {
        String eventId = createEvent(unique("Dup"));
        String artist = createArtist(unique("Echo"));
        mockMvc.perform(put("/api/v1/admin/events/{id}/artists", eventId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"artists": [{"artistId": "%s"}, {"artistId": "%s"}]}
                                """.formatted(artist, artist)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSortPropertyIs400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/events").header("Authorization", admin).param("sort", "nope"))
                .andExpect(status().isBadRequest());
    }
}
