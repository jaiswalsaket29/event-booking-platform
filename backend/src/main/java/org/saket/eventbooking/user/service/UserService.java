package org.saket.eventbooking.user.service;


import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.user.repository.UserRepository;
import org.saket.eventbooking.user.entity.User;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public boolean existsByEmail(String email) {
        return  userRepository.existsByEmail(email);
    }

    public User save(User user) {
        return userRepository.save(user);
    }
}
