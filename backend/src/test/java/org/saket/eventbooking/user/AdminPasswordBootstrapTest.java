package org.saket.eventbooking.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.service.AdminPasswordBootstrap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.saket.eventbooking.user.service.AdminPasswordBootstrap.DEV_ADMIN_PASSWORD;
import static org.saket.eventbooking.user.service.AdminPasswordBootstrap.SEEDED_ADMIN_EMAIL;

/** Replaces the seeded admin's dev password from configuration. Restores it afterwards for other tests. */
class AdminPasswordBootstrapTest extends IntegrationTest {

    @Autowired AdminPasswordBootstrap bootstrap;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RefreshTokenService refreshTokenService;

    private User admin() {
        return userRepository.findByEmail(SEEDED_ADMIN_EMAIL).orElseThrow();
    }

    @AfterEach
    void restoreDevPassword() {
        User admin = admin();
        admin.setPasswordHash(passwordEncoder.encode(DEV_ADMIN_PASSWORD));
        userRepository.save(admin);
    }

    @Test
    void theSeededAdminStartsWithTheDevPassword() {
        assertThat(bootstrap.adminStillUsesDevPassword()).isTrue();
    }

    @Test
    void setsTheConfiguredPasswordOnceAndSignsTheAdminOut() {
        String refreshToken = refreshTokenService.issue(admin());

        assertThat(bootstrap.setPassword("a-long-production-password")).isTrue();

        assertThat(passwordEncoder.matches("a-long-production-password", admin().getPasswordHash())).isTrue();
        assertThat(bootstrap.adminStillUsesDevPassword()).isFalse();
        assertThatThrownBy(() -> refreshTokenService.rotate(refreshToken)).isInstanceOf(RuntimeException.class);

        // restarting with the same value is a no-op
        assertThat(bootstrap.setPassword("a-long-production-password")).isFalse();
    }

    @Test
    void rejectsWeakOrDevPasswords() {
        assertThatThrownBy(() -> bootstrap.setPassword("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap.setPassword(DEV_ADMIN_PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThat(bootstrap.adminStillUsesDevPassword()).isTrue();
    }
}
