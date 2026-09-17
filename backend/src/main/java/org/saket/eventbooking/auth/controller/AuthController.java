package org.saket.eventbooking.auth.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.service.AuthService;
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

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}