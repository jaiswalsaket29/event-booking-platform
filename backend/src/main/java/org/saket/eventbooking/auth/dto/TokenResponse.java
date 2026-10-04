package org.saket.eventbooking.auth.dto;

public record TokenResponse(String accessToken, String refreshToken) {
}
