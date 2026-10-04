package org.saket.eventbooking.auth.oauth2;

/** The parts of Google's OIDC user info we rely on. {@code subject} is Google's stable user id. */
public record GoogleProfile(String subject, String email, boolean emailVerified, String name) {
}
