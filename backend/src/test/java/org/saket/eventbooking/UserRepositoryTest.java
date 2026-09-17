package org.saket.eventbooking;


import org.junit.jupiter.api.Test;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.repository.UserRepository;
import org.saket.eventbooking.user.enums.AuthProvider;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:16");

    @Autowired
    UserRepository userRepository;

    @Test
    void saveAndRetrievesUser(){
        User user = new User();

        user.setName("Test User");
        user.setEmail("test@example.com");
        user.setPasswordHash("hashed");
        user.setRole(Role.USER);
        user.setEmailVerified(false);
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setCreatedAt(Instant.now());

        User userSaved = userRepository.save(user);

        assertThat(userRepository.findById(userSaved.getId())).isPresent();
    }
}
