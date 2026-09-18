package org.saket.eventbooking.auth.dto;

import org.saket.eventbooking.user.dto.UserResponse;

public record AuthResponse(String accessToken, UserResponse user) {}