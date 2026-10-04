package org.saket.eventbooking.user.service;


import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.user.repository.UserRepository;
import org.saket.eventbooking.user.entity.User;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    /** Emails are case-insensitive identifiers; store and look them up in one canonical form. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    public Optional<User> findByProviderId(String providerId) {
        return userRepository.findByProviderId(providerId);
    }

    public boolean existsByEmail(String email) {
        return  userRepository.existsByEmail(email);
    }

    public User save(User user) {
        return userRepository.save(user);
    }
}
