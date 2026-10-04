package org.saket.eventbooking.support;

import org.saket.eventbooking.common.security.JwtTokenProvider;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.AuthProvider;
import org.saket.eventbooking.user.enums.Role;
import org.saket.eventbooking.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

/**
 * Base for Testcontainers + MockMvc integration tests. The database is shared by all test classes
 * (cached context), so tests create their own uniquely-named data instead of assuming an empty DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @Autowired
    protected RecordingEmailService emailService;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.dev";
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected User createUser(Role role, boolean emailVerified) {
        User user = new User();
        user.setName("Test " + role.name().toLowerCase());
        user.setEmail(uniqueEmail());
        user.setRole(role);
        user.setEmailVerified(emailVerified);
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    protected String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user);
    }

    protected String userToken() {
        return bearer(createUser(Role.USER, true));
    }

    protected String adminToken() {
        return bearer(createUser(Role.ADMIN, true));
    }
}
