package org.saket.eventbooking.user.dto;

import org.saket.eventbooking.user.enums.Role;

import java.util.UUID;

public record UserResponse(UUID id, String name, String email, Role role, boolean emailVerified) {
}
