package org.saket.eventbooking.auth;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.EmailVerificationToken;
import org.saket.eventbooking.user.repository.EmailVerificationTokenRepository;
import org.saket.eventbooking.common.security.SecureTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailVerificationTest extends IntegrationTest {

    @Autowired
    EmailVerificationTokenRepository tokenRepository;

    private String signup(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Meera Iyer", "email": "%s", "password": "password123"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(false));
        return emailService.lastTokenSentTo(email);
    }

    private void verify(String token, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\"}".formatted(token)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void signupSendsVerificationLinkAndVerifyMarksUserVerified() throws Exception {
        String email = uniqueEmail();
        String token = signup(email);

        assertThat(emailService.lastTo(email).orElseThrow().body()).contains("http://localhost:5173/verify-email?token=");
        // only the hash is persisted
        assertThat(tokenRepository.findByToken(token)).isEmpty();
        assertThat(tokenRepository.findByToken(SecureTokens.sha256(token))).isPresent();

        verify(token, 200);

        assertThat(userRepository.findByEmail(email).orElseThrow().getEmailVerified()).isTrue();
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String token = signup(uniqueEmail());
        verify(token, 200);
        verify(token, 400);
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        verify("not-a-real-token", 400);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        String token = signup(email);
        EmailVerificationToken entity = tokenRepository.findByToken(SecureTokens.sha256(token)).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        tokenRepository.save(entity);

        verify(token, 400);
        assertThat(userRepository.findByEmail(email).orElseThrow().getEmailVerified()).isFalse();
    }

    @Test
    void resendIssuesNewTokenAndInvalidatesTheOldOne() throws Exception {
        String email = uniqueEmail();
        String oldToken = signup(email);

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\"}".formatted(email.toUpperCase())))
                .andExpect(status().isOk());

        String newToken = emailService.lastTokenSentTo(email);
        assertThat(newToken).isNotEqualTo(oldToken);
        verify(oldToken, 400);
        verify(newToken, 200);
    }

    @Test
    void resendForUnknownEmailLooksIdenticalAndSendsNothing() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\"}".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
        assertThat(emailService.countTo(email)).isZero();
    }

    @Test
    void resendForAlreadyVerifiedUserSendsNothing() throws Exception {
        String email = uniqueEmail();
        verify(signup(email), 200);
        long before = emailService.countTo(email);

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\"}".formatted(email)))
                .andExpect(status().isOk());
        assertThat(emailService.countTo(email)).isEqualTo(before);
    }
}
