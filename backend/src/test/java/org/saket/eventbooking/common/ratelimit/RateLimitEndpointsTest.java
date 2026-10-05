package org.saket.eventbooking.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default limits from application.yml: login 30/IP and 10/email, forgot-password 10/IP and 3/email,
 * bookings 10/user. Each test uses its own client IP so the shared Redis buckets don't leak between tests.
 */
class RateLimitEndpointsTest extends IntegrationTest {

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String uniqueIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    private static String loginBody(String email) {
        return "{\"email\": \"" + email + "\", \"password\": \"wrong-password\"}";
    }

    @Test
    void loginIsLimitedPerEmailAcrossIps() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(fromIp(uniqueIp()))
                            .contentType(MediaType.APPLICATION_JSON).content(loginBody(email)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login").with(fromIp(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(email.toUpperCase())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message", containsString("Too many requests")));

        // other accounts are unaffected
        mockMvc.perform(post("/api/v1/auth/login").with(fromIp(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(uniqueEmail())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsLimitedPerIpAcrossEmails() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(fromIp(ip))
                            .contentType(MediaType.APPLICATION_JSON).content(loginBody(uniqueEmail())))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login").with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(uniqueEmail())))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/v1/auth/login").with(fromIp(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(uniqueEmail())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPasswordIsLimitedPerEmail() throws Exception {
        String body = "{\"email\": \"" + uniqueEmail() + "\"}";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/forgot-password").with(fromIp(uniqueIp()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/auth/forgot-password").with(fromIp(uniqueIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void forgotPasswordIsLimitedPerIp() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/forgot-password").with(fromIp(ip))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + uniqueEmail() + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/auth/forgot-password").with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + uniqueEmail() + "\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void bookingCreationIsLimitedPerUser() throws Exception {
        String token = userToken();
        String body = "{\"sessionId\": \"" + UUID.randomUUID() + "\", \"quantity\": 1}";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/bookings").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/v1/bookings").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        // another user isn't affected
        mockMvc.perform(post("/api/v1/bookings").header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }
}
