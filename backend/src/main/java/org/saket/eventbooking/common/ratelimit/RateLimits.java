package org.saket.eventbooking.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * The application's rate limits, checked at the top of the protected endpoints. Login and forgot-password
 * are limited per client IP (one machine trying many accounts) and per email (many machines trying one
 * account); booking creation per user (hold-and-abandon would lock seats away from everyone else).
 *
 * <p>Client IP is {@link HttpServletRequest#getRemoteAddr()}. Behind a reverse proxy, let Tomcat's
 * RemoteIpValve rewrite it from {@code X-Forwarded-For} for trusted proxies only
 * ({@code server.forward-headers-strategy: native}); never read the header directly, since any client
 * can send one.
 */
@Component
@RequiredArgsConstructor
public class RateLimits {

    private final TokenBucketRateLimiter limiter;
    private final RateLimitProperties properties;

    public void login(HttpServletRequest request, String email) {
        if (properties.enabled()) {
            limiter.consume("login-ip", request.getRemoteAddr(), properties.loginPerIp());
            limiter.consume("login-email", normalize(email), properties.loginPerEmail());
        }
    }

    public void forgotPassword(HttpServletRequest request, String email) {
        if (properties.enabled()) {
            limiter.consume("forgot-password-ip", request.getRemoteAddr(), properties.forgotPasswordPerIp());
            limiter.consume("forgot-password-email", normalize(email), properties.forgotPasswordPerEmail());
        }
    }

    public void bookingCreation(UUID userId) {
        if (properties.enabled()) {
            limiter.consume("booking-user", userId.toString(), properties.bookingPerUser());
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
