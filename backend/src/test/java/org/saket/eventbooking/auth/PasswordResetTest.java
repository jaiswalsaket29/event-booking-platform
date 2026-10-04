package org.saket.eventbooking.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetTest extends IntegrationTest {

    private static final String GENERIC_MESSAGE = "If an account exists for that email, a password reset link has been sent";

    private void signup(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Kabir Sen", "email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());
    }

    private String loginForRefreshToken(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.refreshToken");
    }

    private void login(String email, String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().is(expectedStatus));
    }

    private void forgot(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\"}".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE));
    }

    private void reset(String token, String newPassword, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "%s"}
                                """.formatted(token, newPassword)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void fullResetFlowChangesPasswordAndRevokesRefreshTokens() throws Exception {
        String email = uniqueEmail();
        signup(email, "oldPassword1");
        String refreshToken = loginForRefreshToken(email, "oldPassword1");

        forgot(email);
        String token = emailService.lastTokenSentTo(email);
        assertThat(emailService.lastTo(email).orElseThrow().body()).contains("/reset-password?token=");

        reset(token, "newPassword1", 200);

        login(email, "oldPassword1", 401);
        login(email, "newPassword1", 200);
        // every refresh token issued before the reset is dead
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByEmail(email).orElseThrow().getEmailVerified()).isTrue();
    }

    @Test
    void unknownEmailGetsTheSameResponseAndNoEmail() throws Exception {
        String email = uniqueEmail();
        forgot(email);
        assertThat(emailService.countTo(email)).isZero();
    }

    @Test
    void resetTokenIsSingleUse() throws Exception {
        String email = uniqueEmail();
        signup(email, "oldPassword1");
        forgot(email);
        String token = emailService.lastTokenSentTo(email);

        reset(token, "newPassword1", 200);
        reset(token, "anotherPassword1", 400);
        login(email, "newPassword1", 200);
    }

    @Test
    void newRequestInvalidatesPreviousToken() throws Exception {
        String email = uniqueEmail();
        signup(email, "oldPassword1");
        forgot(email);
        String first = emailService.lastTokenSentTo(email);
        forgot(email);
        String second = emailService.lastTokenSentTo(email);

        reset(first, "newPassword1", 400);
        reset(second, "newPassword1", 200);
    }

    @Test
    void weakNewPasswordIsRejected() throws Exception {
        reset("any-token", "short", 400);
    }
}
