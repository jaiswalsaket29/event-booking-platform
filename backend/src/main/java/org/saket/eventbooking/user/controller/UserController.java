package org.saket.eventbooking.user.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.user.dto.UserResponse;
import org.saket.eventbooking.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** The principal set by JwtAuthenticationFilter is the user's UUID. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        return UserResponse.from(userService.getById(userId));
    }
}
