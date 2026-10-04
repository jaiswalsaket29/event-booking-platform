package org.saket.eventbooking.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.dto.EmailRequest;
import org.saket.eventbooking.common.dto.MessageResponse;
import org.saket.eventbooking.auth.dto.TokenRequest;
import org.saket.eventbooking.user.service.EmailVerificationService;
import org.saket.eventbooking.user.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest request) {
        emailVerificationService.verify(request.token());
        return new MessageResponse("Email verified");
    }

    /** Always 200 with the same message, whether or not the email is registered or already verified. */
    @PostMapping("/resend-verification")
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest request) {
        emailVerificationService.resend(UserService.normalizeEmail(request.email()));
        return new MessageResponse("If that account exists and is unverified, a new verification email has been sent");
    }
}
