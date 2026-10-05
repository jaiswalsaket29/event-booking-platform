package org.saket.eventbooking.user.service;

import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.auth.service.RefreshTokenService;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.repository.UserRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * The seeded admin account (Flyway V6) ships with a dev password. When {@code app.admin.password}
 * ({@code ADMIN_PASSWORD}) is set, the admin's password is set to it at startup, before the web server
 * accepts requests, so the environment is the source of truth. Changing it signs the admin out everywhere.
 */
@Slf4j
@Component
public class AdminPasswordBootstrap implements SmartInitializingSingleton {

    public static final String SEEDED_ADMIN_EMAIL = "admin@eventbooking.dev";
    public static final String DEV_ADMIN_PASSWORD = "Admin@12345";
    static final int MIN_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final TransactionTemplate transactions;
    private final String configuredPassword;

    public AdminPasswordBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  RefreshTokenService refreshTokenService, TransactionTemplate transactions,
                                  @Value("${app.admin.password:}") String configuredPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.transactions = transactions;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void afterSingletonsInstantiated() {
        applyConfiguredPassword();
    }

    /** Idempotent: does nothing when no password is configured or the admin already has it. */
    public void applyConfiguredPassword() {
        if (StringUtils.hasText(configuredPassword)) {
            setPassword(configuredPassword);
        }
    }

    /** Sets the seeded admin's password unless it's already that password. Returns whether it changed. */
    public boolean setPassword(String rawPassword) {
        if (rawPassword.length() < MIN_LENGTH || rawPassword.equals(DEV_ADMIN_PASSWORD)) {
            throw new IllegalStateException("app.admin.password must be at least " + MIN_LENGTH
                    + " characters and not the dev default");
        }
        Boolean changed = transactions.execute(status -> {
            User admin = userRepository.findByEmail(SEEDED_ADMIN_EMAIL).orElse(null);
            if (admin == null) {
                log.warn("app.admin.password is set but there is no {} account", SEEDED_ADMIN_EMAIL);
                return false;
            }
            if (admin.getPasswordHash() != null && passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
                return false;
            }
            admin.setPasswordHash(passwordEncoder.encode(rawPassword));
            refreshTokenService.revokeAllForUser(admin.getId());
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            log.info("Admin password for {} set from app.admin.password", SEEDED_ADMIN_EMAIL);
        }
        return Boolean.TRUE.equals(changed);
    }

    /** True while the seeded admin can still log in with the published dev password. */
    public boolean adminStillUsesDevPassword() {
        return userRepository.findByEmail(SEEDED_ADMIN_EMAIL)
                .map(User::getPasswordHash)
                .filter(hash -> passwordEncoder.matches(DEV_ADMIN_PASSWORD, hash))
                .isPresent();
    }
}
