package org.saket.eventbooking.common.config;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OpenAPI spec and Swagger UI are public (in dev) and describe the real endpoints. */
class OpenApiDocsTest extends IntegrationTest {

    @Test
    void specIsPublicAndCoversTheApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Event Booking API"))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/events")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/bookings/{bookingId}/payments")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/admin/uploads/images")))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    /** The authenticated user comes from the JWT, never from a request parameter. */
    @Test
    void principalParametersAreNotExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/bookings'].post.parameters").doesNotExist())
                .andExpect(content().string(not(containsString("\"name\":\"userId\""))));
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }
}
