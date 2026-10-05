package org.saket.eventbooking.common.config;

import org.saket.eventbooking.user.service.AdminPasswordBootstrap;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Under the {@code prod} profile, refuses to start (before the web server opens) while any published
 * dev default is still in use: the JWT secret, the payment webhook secret, or the seeded admin's
 * password. Failing at startup is louder and safer than a production site anyone can forge tokens for.
 */
@Component
@Profile("prod")
public class ProductionReadinessCheck implements SmartInitializingSingleton {

    static final int MIN_SECRET_BYTES = 32;
    static final String DEV_WEBHOOK_SECRET = "dev-only-webhook-secret-change-me";

    private final AdminPasswordBootstrap adminPasswordBootstrap;
    private final String jwtSecret;
    private final String webhookSecret;

    public ProductionReadinessCheck(AdminPasswordBootstrap adminPasswordBootstrap,
                                    @Value("${jwt.secret}") String jwtSecret,
                                    @Value("${app.payments.webhook-secret}") String webhookSecret) {
        this.adminPasswordBootstrap = adminPasswordBootstrap;
        this.jwtSecret = jwtSecret;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public void afterSingletonsInstantiated() {
        adminPasswordBootstrap.applyConfiguredPassword(); // so ADMIN_PASSWORD counts, whatever the init order
        List<String> problems = new ArrayList<>(secretProblems(jwtSecret, webhookSecret));
        if (adminPasswordBootstrap.adminStillUsesDevPassword()) {
            problems.add("the seeded admin still has the dev password; set ADMIN_PASSWORD");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with the prod profile: " + String.join("; ", problems));
        }
    }

    static List<String> secretProblems(String jwtSecret, String webhookSecret) {
        List<String> problems = new ArrayList<>();
        if (weak(jwtSecret)) {
            problems.add("JWT_SECRET must be a random value of at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (weak(webhookSecret) || DEV_WEBHOOK_SECRET.equals(webhookSecret)) {
            problems.add("PAYMENT_WEBHOOK_SECRET must be a random value of at least " + MIN_SECRET_BYTES + " bytes");
        }
        return problems;
    }

    /** Too short, or one of the placeholders from .env.example / the test profile. */
    private static boolean weak(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            return true;
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        return lower.contains("change-me") || lower.contains("test-secret") || lower.contains("dev-only");
    }
}
