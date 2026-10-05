package org.saket.eventbooking.media;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests run without R2 credentials: uploads are off and images are added by URL. */
class ImageUploadEndpointTest extends IntegrationTest {

    private static final String PNG_REQUEST = """
            {"purpose": "EVENT", "contentType": "image/png", "contentLength": 2048}
            """;

    @Test
    void optionsReportThatDirectUploadIsOff() throws Exception {
        mockMvc.perform(get("/api/v1/admin/uploads/images").header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directUpload").value(false))
                .andExpect(jsonPath("$.maxBytes").value(5 * 1024 * 1024));
    }

    @Test
    void presignIs501WhenNoStorageIsConfigured() throws Exception {
        mockMvc.perform(post("/api/v1/admin/uploads/images").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(PNG_REQUEST))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.message").value(
                        "Direct image upload isn't configured on this server; submit an image URL instead"));
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/uploads/images").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\": \"EVENT\", \"contentType\": \"image/png\", \"contentLength\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("contentLength"));
    }

    @Test
    void onlyAdminsCanAskForUploadUrls() throws Exception {
        mockMvc.perform(post("/api/v1/admin/uploads/images").header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON).content(PNG_REQUEST))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/uploads/images")
                        .contentType(MediaType.APPLICATION_JSON).content(PNG_REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void imageUrlsMustBeHttp() throws Exception {
        String eventId = fixtures.publishedEvent(unique("Url Check")).id().toString();
        mockMvc.perform(post("/api/v1/admin/events/{id}/images", eventId).header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"javascript:alert(1)\", \"sortOrder\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("imageUrl"));
        mockMvc.perform(post("/api/v1/admin/events/{id}/images", eventId).header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"https://img.example.test/poster.webp\", \"sortOrder\": 0}"))
                .andExpect(status().isCreated());
    }
}
