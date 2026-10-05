package org.saket.eventbooking.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.AuthResponse;
import org.saket.eventbooking.auth.service.AuthService;
import org.saket.eventbooking.common.ratelimit.RateLimits;
import org.saket.eventbooking.user.dto.LoginRequest;
import org.saket.eventbooking.user.dto.SignupRequest;
import org.saket.eventbooking.user.dto.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimits rateLimits;

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    /** Rate-limited per client IP and per email (429 with Retry-After). */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimits.login(http, request.email());
        return ResponseEntity.ok(authService.login(request));
    }
}