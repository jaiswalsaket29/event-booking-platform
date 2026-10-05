package org.saket.eventbooking.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.auth.dto.TokenResponse;
import org.saket.eventbooking.auth.exception.InvalidRefreshTokenException;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshTokenTest extends IntegrationTest {

    @Autowired RefreshTokenService refreshTokenService;

    private String signupAndLoginForRefreshToken() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Nisha Rao\", \"email\": \"%s\", \"password\": \"password123\"}".formatted(email)))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"password123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.refreshToken");
    }

    private ResultActions refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"%s\"}".formatted(token)));
    }

    @Test
    void refreshRotatesAndTheOldTokenCanNotBeReplayed() throws Exception {
        String original = signupAndLoginForRefreshToken();

        String body = refresh(original)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String rotated = JsonPath.read(body, "$.refreshToken");
        String accessToken = JsonPath.read(body, "$.accessToken");
        assertThat(rotated).isNotEqualTo(original);

        // the new access token works
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // replaying the rotated-out token is rejected; the new one still works once
        refresh(original)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token has already been used or revoked"));
        refresh(rotated).andExpect(status().isOk());
    }

    @Test
    void logoutRevokesTheToken() throws Exception {
        String token = signupAndLoginForRefreshToken();
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(token)))
                .andExpect(status().isNoContent());
        refresh(token).andExpect(status().isUnauthorized());
    }

    /** Two tabs refreshing with the same token at once: exactly one rotation may succeed. */
    @Test
    void concurrentRefreshesOfOneTokenRotateExactlyOnce() throws Exception {
        String token = signupAndLoginForRefreshToken();
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<TokenResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return refreshTokenService.rotate(token);
            }));
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int succeeded = 0;
        for (Future<TokenResponse> result : results) {
            try {
                result.get();
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(InvalidRefreshTokenException.class);
            }
        }
        assertThat(succeeded).isEqualTo(1);
    }
}
