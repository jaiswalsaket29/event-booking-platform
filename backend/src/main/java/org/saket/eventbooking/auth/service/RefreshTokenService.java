package org.saket.eventbooking.auth.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.entity.RefreshToken;
import org.saket.eventbooking.auth.exception.InvalidRefreshTokenException;
import org.saket.eventbooking.auth.repository.RefreshTokenRepository;
import org.saket.eventbooking.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.jwt.refresh-token-expiry-days:30}")
    private int refreshTokenExpiryDays;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Issues a new refresh token for the user, returning the RAW token (only ever seen once — not the hash). */
    public String issue(User user) {
        String rawToken = generateRawToken();

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(rawToken));
        entity.setExpiresAt(Instant.now().plus(refreshTokenExpiryDays, ChronoUnit.DAYS));
        entity.setRevoked(false);
        entity.setCreatedAt(Instant.now());
        refreshTokenRepository.save(entity);

        return rawToken;
    }

    /**
     * Validates a raw refresh token, revokes it, and returns the associated user —
     * rotation happens here: caller is expected to call issue() again for the replacement.
     */
    public User validateAndRevoke(String rawToken) {
        RefreshToken entity = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (Boolean.TRUE.equals(entity.getRevoked())) {
            // Replay of an already-used/revoked token — a real theft-detection response
            // (revoke the whole family) is future scope; flag-and-reject is enough for now.
            throw new InvalidRefreshTokenException("Refresh token has already been used or revoked");
        }
        if (entity.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        entity.setRevoked(true);
        refreshTokenRepository.save(entity);

        return entity.getUser();
    }

    /** Logout: revoke without issuing a replacement. */
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(entity -> {
                    entity.setRevoked(true);
                    refreshTokenRepository.save(entity);
                });
        // Intentionally silent if not found/already revoked — logout should be idempotent,
        // not leak whether a given token string was ever valid.
    }

    private String generateRawToken() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes());
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}