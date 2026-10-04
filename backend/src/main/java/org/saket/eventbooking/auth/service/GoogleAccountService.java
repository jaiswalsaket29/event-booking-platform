package org.saket.eventbooking.auth.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.auth.exception.OAuth2LoginRejectedException;
import org.saket.eventbooking.auth.oauth2.GoogleProfile;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.AuthProvider;
import org.saket.eventbooking.user.enums.Role;
import org.saket.eventbooking.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Maps a Google identity to a local account:
 * 1. a user already linked to this Google subject;
 * 2. otherwise an existing account with the same email, linked only if Google says the email is verified;
 * 3. otherwise a new USER account (verified iff Google verified the email).
 * Admins never sign in through Google.
 */
@Service
@RequiredArgsConstructor
public class GoogleAccountService {

    private final UserService userService;

    @Transactional
    public User loginOrRegister(GoogleProfile profile) {
        if (profile.subject() == null || profile.email() == null) {
            throw new OAuth2LoginRejectedException("Google did not return an email address");
        }
        String email = UserService.normalizeEmail(profile.email());

        User user = userService.findByProviderId(profile.subject())
                .orElseGet(() -> userService.findByEmail(email)
                        .map(existing -> link(existing, profile))
                        .orElseGet(() -> register(profile, email)));

        if (user.getRole() == Role.ADMIN) {
            throw new OAuth2LoginRejectedException("Admin accounts must sign in with email and password");
        }
        return user;
    }

    private User link(User existing, GoogleProfile profile) {
        // Without Google's verification, anyone could create a Google account claiming this email
        // and take over the local account.
        if (!profile.emailVerified()) {
            throw new OAuth2LoginRejectedException("Google has not verified this email address");
        }
        if (existing.getRole() == Role.ADMIN) {
            throw new OAuth2LoginRejectedException("Admin accounts must sign in with email and password");
        }
        if (existing.getProviderId() != null) {
            throw new OAuth2LoginRejectedException("This account is already linked to a different Google account");
        }
        existing.setProviderId(profile.subject());
        existing.setEmailVerified(true);
        // authProvider stays as-is: it records how the account was created, not every way it can log in.
        return userService.save(existing);
    }

    private User register(GoogleProfile profile, String email) {
        User user = new User();
        user.setName(profile.name() != null && !profile.name().isBlank() ? profile.name().trim() : email);
        user.setEmail(email);
        user.setPasswordHash(null); // Google-only account
        user.setRole(Role.USER);
        user.setAuthProvider(AuthProvider.GOOGLE);
        user.setProviderId(profile.subject());
        user.setEmailVerified(profile.emailVerified());
        user.setCreatedAt(Instant.now());
        return userService.save(user);
    }
}
