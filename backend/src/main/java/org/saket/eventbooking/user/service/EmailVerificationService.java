package org.saket.eventbooking.user.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.email.EmailService;
import org.saket.eventbooking.common.security.SecureTokens;
import org.saket.eventbooking.user.entity.EmailVerificationToken;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.exception.InvalidTokenException;
import org.saket.eventbooking.user.repository.EmailVerificationTokenRepository;
import org.saket.eventbooking.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.auth.email-verification-token-ttl:24h}")
    private Duration tokenTtl;

    @Value("${app.frontend.user-url:http://localhost:5173}")
    private String userFrontendUrl;

    /** Invalidates any outstanding token, issues a fresh one, and emails the verification link. */
    @Transactional
    public void issue(User user) {
        tokenRepository.invalidateAllForUser(user.getId());

        String rawToken = SecureTokens.generate(32);
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setToken(SecureTokens.sha256(rawToken)); // only the hash is stored
        token.setExpiresAt(Instant.now().plus(tokenTtl));
        token.setUsed(false);
        tokenRepository.save(token);

        String link = userFrontendUrl + "/verify-email?token=" + rawToken;
        emailService.send(user.getEmail(), "Verify your email",
                "Hi " + user.getName() + ",\n\nConfirm your email address by opening this link:\n" + link
                        + "\n\nThe link expires in " + tokenTtl.toHours() + " hours.");
    }

    @Transactional
    public void verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByToken(SecureTokens.sha256(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Invalid verification token"));
        if (token.isUsed()) {
            throw new InvalidTokenException("Verification token has already been used");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Verification token has expired");
        }

        token.setUsed(true);
        token.getUser().setEmailVerified(true);
    }

    /**
     * Re-sends the link if the email belongs to an unverified account. Silent otherwise,
     * so the endpoint can't be used to discover which emails are registered.
     */
    @Transactional
    public void resend(String email) {
        userRepository.findByEmail(email)
                .filter(user -> !Boolean.TRUE.equals(user.getEmailVerified()))
                .ifPresent(this::issue);
    }
}
