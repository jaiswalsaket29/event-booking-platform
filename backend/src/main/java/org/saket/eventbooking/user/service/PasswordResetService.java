package org.saket.eventbooking.user.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import org.saket.eventbooking.common.email.EmailService;
import org.saket.eventbooking.common.security.SecureTokens;
import org.saket.eventbooking.user.entity.PasswordResetToken;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.exception.InvalidTokenException;
import org.saket.eventbooking.user.repository.PasswordResetTokenRepository;
import org.saket.eventbooking.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;

    @Value("${app.auth.password-reset-token-ttl:30m}")
    private Duration tokenTtl;

    @Value("${app.frontend.user-url:http://localhost:5173}")
    private String userFrontendUrl;

    /**
     * Emails a reset link if the account exists. Does nothing otherwise; the caller always
     * returns the same response, so this can't be used to enumerate accounts.
     */
    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmail(email).ifPresent(this::issue);
    }

    private void issue(User user) {
        tokenRepository.invalidateAllForUser(user.getId());

        String rawToken = SecureTokens.generate(32);
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(SecureTokens.sha256(rawToken)); // only the hash is stored
        token.setExpiresAt(Instant.now().plus(tokenTtl));
        token.setUsed(false);
        tokenRepository.save(token);

        String link = userFrontendUrl + "/reset-password?token=" + rawToken;
        emailService.send(user.getEmail(), "Reset your password",
                "Hi " + user.getName() + ",\n\nReset your password by opening this link:\n" + link
                        + "\n\nThe link expires in " + tokenTtl.toMinutes() + " minutes."
                        + " If you didn't ask for this, you can ignore this email.");
    }

    /** Sets the new password, burns the token, and revokes every refresh token (signs out all devices). */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = tokenRepository.findByToken(SecureTokens.sha256(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Invalid password reset token"));
        if (token.isUsed()) {
            throw new InvalidTokenException("Password reset token has already been used");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Password reset token has expired");
        }

        token.setUsed(true);
        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Following the emailed link proves the user controls the address.
        user.setEmailVerified(true);

        refreshTokenService.revokeAllForUser(user.getId());
    }
}
