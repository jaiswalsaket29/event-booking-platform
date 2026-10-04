package org.saket.eventbooking.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.exception.OAuth2LoginRejectedException;
import org.saket.eventbooking.auth.service.GoogleAccountService;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import org.saket.eventbooking.common.security.JwtTokenProvider;
import org.saket.eventbooking.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Ends the Google login in our own tokens: maps the Google identity to a local user, issues the usual
 * access + refresh token pair, and redirects to the user frontend with them in the URL fragment
 * (fragments are never sent to servers or written to access logs).
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final GoogleAccountService googleAccountService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.frontend.user-url:http://localhost:5173}")
    private String userFrontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        GoogleProfile profile = new GoogleProfile(
                principal.getAttribute("sub"),
                principal.getAttribute("email"),
                Boolean.TRUE.equals(principal.getAttribute("email_verified")),
                principal.getAttribute("name"));

        String target;
        try {
            User user = googleAccountService.loginOrRegister(profile);
            String accessToken = jwtTokenProvider.generateAccessToken(user);
            String refreshToken = refreshTokenService.issue(user);
            target = callbackUrl() + "#accessToken=" + encode(accessToken) + "&refreshToken=" + encode(refreshToken);
        } catch (OAuth2LoginRejectedException e) {
            target = callbackUrl() + "#error=" + encode(e.getMessage());
        } finally {
            // The HTTP session only carried OAuth2 handshake state; the API itself stays stateless.
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        response.sendRedirect(target);
    }

    private String callbackUrl() {
        return userFrontendUrl + "/oauth2/callback";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
