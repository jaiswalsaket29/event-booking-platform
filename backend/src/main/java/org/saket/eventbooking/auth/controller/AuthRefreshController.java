package org.saket.eventbooking.auth.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.RefreshRequest;
import org.saket.eventbooking.auth.dto.TokenResponse;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthRefreshController {

    private final RefreshTokenService refreshTokenService;

    /** Rotation: the presented refresh token is revoked and a new access + refresh pair is returned. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(refreshTokenService.rotate(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}