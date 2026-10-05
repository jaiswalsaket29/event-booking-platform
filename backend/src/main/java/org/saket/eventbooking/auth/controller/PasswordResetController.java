package org.saket.eventbooking.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.EmailRequest;
import org.saket.eventbooking.common.dto.MessageResponse;
import org.saket.eventbooking.common.ratelimit.RateLimits;
import org.saket.eventbooking.auth.dto.ResetPasswordRequest;
import org.saket.eventbooking.user.service.PasswordResetService;
import org.saket.eventbooking.user.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final RateLimits rateLimits;

    /**
     * Always 200 with the same message: no account enumeration. Rate-limited per client IP and per email,
     * so it can't be used to flood someone's inbox; a 429 also says nothing about whether the account exists.
     */
    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        rateLimits.forgotPassword(http, request.email());
        passwordResetService.requestReset(UserService.normalizeEmail(request.email()));
        return new MessageResponse("If an account exists for that email, a password reset link has been sent");
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return new MessageResponse("Password has been reset. Please log in again.");
    }
}
