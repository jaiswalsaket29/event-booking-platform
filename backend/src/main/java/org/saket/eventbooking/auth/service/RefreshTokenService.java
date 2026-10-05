package org.saket.eventbooking.auth.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.TokenResponse;
import org.saket.eventbooking.auth.entity.RefreshToken;
import org.saket.eventbooking.auth.exception.InvalidRefreshTokenException;
import org.saket.eventbooking.auth.repository.RefreshTokenRepository;
import org.saket.eventbooking.common.security.JwtTokenProvider;
import org.saket.eventbooking.common.security.SecureTokens;
import org.saket.eventbooking.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.refresh-token-expiry-days:30}")
    private int refreshTokenExpiryDays;

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
     * Refresh-token rotation in one transaction: lock the presented token's row, reject it if it was
     * already used/revoked or has expired, revoke it, and issue a new access + refresh pair.
     * The lock means two simultaneous refreshes with the same token (two tabs) rotate it exactly once;
     * the loser sees it revoked and gets 401.
     */
    @Transactional
    public TokenResponse rotate(String rawToken) {
        RefreshToken entity = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (Boolean.TRUE.equals(entity.getRevoked())) {
            // Replay of an already-used/revoked token. A real theft-detection response
            // (revoke the whole family) is future scope; flag-and-reject is enough for now.
            throw new InvalidRefreshTokenException("Refresh token has already been used or revoked");
        }
        if (entity.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        entity.setRevoked(true);
        User user = entity.getUser(); // loaded inside this transaction
        return new TokenResponse(jwtTokenProvider.generateAccessToken(user), issue(user));
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

    /** Signs the user out everywhere (e.g. after a password reset). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }

    private String generateRawToken() {
        return SecureTokens.generate(64);
    }

    private String hash(String rawToken) {
        return SecureTokens.sha256(rawToken);
    }
}