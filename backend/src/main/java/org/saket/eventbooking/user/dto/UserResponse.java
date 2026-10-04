package org.saket.eventbooking.user.dto;

import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;

import java.util.UUID;

public record UserResponse(UUID id, String name, String email, Role role, boolean emailVerified) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), Boolean.TRUE.equals(user.getEmailVerified()));
    }
}
