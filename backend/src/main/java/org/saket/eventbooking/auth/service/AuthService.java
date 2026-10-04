package org.saket.eventbooking.auth.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.AuthResponse;
import org.saket.eventbooking.auth.exception.EmailAlreadyExistsException;
import org.saket.eventbooking.auth.exception.InvalidCredentialsException;
import org.saket.eventbooking.common.security.JwtTokenProvider;
import org.saket.eventbooking.user.dto.LoginRequest;
import org.saket.eventbooking.user.dto.SignupRequest;
import org.saket.eventbooking.user.dto.UserResponse;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.AuthProvider;
import org.saket.eventbooking.user.enums.Role;
import org.saket.eventbooking.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public UserResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (userService.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password())); // never store plaintext
        user.setRole(Role.USER); // hardcoded server-side — payload can never grant ADMIN
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setEmailVerified(false); // per Day 6 decision — informational, doesn't block login
        user.setCreatedAt(Instant.now());

        User saved = userService.save(user);
        return toResponse(saved);
    }

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthResponse login(LoginRequest request) {
        User user = userService.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(InvalidCredentialsException::new);

        // OAuth-only users have a null passwordHash — matches() would NPE, so guard explicitly.
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return new AuthResponse(token,refreshToken, toResponse(user));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.from(user);
    }
}