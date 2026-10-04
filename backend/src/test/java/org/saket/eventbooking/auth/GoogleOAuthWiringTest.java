package org.saket.eventbooking.auth;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoogleOAuthWiringTest {

    @Nested
    class Disabled extends IntegrationTest {

        @Test
        void authorizationEndpointDoesNotExistWithoutTheProfile() throws Exception {
            // Not wired: falls through to the authenticated-by-default rule.
            mockMvc.perform(get("/oauth2/authorization/google"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @ActiveProfiles({"test", "google-oauth"})
    @TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client-id", "GOOGLE_CLIENT_SECRET=test-client-secret"})
    class Enabled extends IntegrationTest {

        @Test
        void authorizationEndpointRedirectsToGoogle() throws Exception {
            mockMvc.perform(get("/oauth2/authorization/google"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", startsWith("https://accounts.google.com/o/oauth2/v2/auth")));
        }
    }
}
