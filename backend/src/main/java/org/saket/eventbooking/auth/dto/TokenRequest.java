package org.saket.eventbooking.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** A one-time token taken from an emailed link (e.g. email verification). */
public record TokenRequest(@NotBlank String token) {
}
