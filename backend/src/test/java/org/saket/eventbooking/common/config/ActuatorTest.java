package org.saket.eventbooking.common.config;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Health is public and says only UP/DOWN; every other actuator endpoint is unreachable. */
class ActuatorTest extends IntegrationTest {

    @Test
    void healthIsPublicAndRevealsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void probesAreAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // readiness includes the database and Redis
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherEndpointsAreLockedDown() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/env", "/actuator/beans", "/actuator/heapdump",
                "/actuator/info", "/actuator/metrics"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", userToken())).andExpect(status().isForbidden());
        }
        // even an admin can't reach endpoints that aren't exposed
        String admin = adminToken();
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/heapdump"}) {
            mockMvc.perform(get(path).header("Authorization", admin)).andExpect(status().isNotFound());
        }
    }
}
