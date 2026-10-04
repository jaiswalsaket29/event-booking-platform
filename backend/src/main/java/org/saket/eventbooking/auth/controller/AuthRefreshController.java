package org.saket.eventbooking.auth.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.RefreshRequest;
import org.saket.eventbooking.auth.dto.TokenResponse;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import org.saket.eventbooking.common.security.JwtTokenProvider;
import org.saket.eventbooking.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthRefreshController {

    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        User user = refreshTokenService.validateAndRevoke(request.refreshToken());

        String newAccessToken = jwtTokenProvider.generateAccessToken(user);
        String newRefreshToken = refreshTokenService.issue(user);

        return ResponseEntity.ok(new TokenResponse(newAccessToken, newRefreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}