package org.saket.eventbooking.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReadinessCheckTest {

    private static final String STRONG = "q8Zr2N5vXw0pL7tY3mB9cK6hF1jD4sGa";

    @Test
    void acceptsRandomSecretsOfAtLeast32Bytes() {
        assertThat(ProductionReadinessCheck.secretProblems(STRONG, STRONG + "x")).isEmpty();
    }

    @Test
    void rejectsShortSecrets() {
        assertThat(ProductionReadinessCheck.secretProblems("too-short", STRONG))
                .singleElement().asString().startsWith("JWT_SECRET");
        assertThat(ProductionReadinessCheck.secretProblems(STRONG, "too-short"))
                .singleElement().asString().startsWith("PAYMENT_WEBHOOK_SECRET");
    }

    @Test
    void rejectsPublishedPlaceholders() {
        // .env.example, the test profile and application.yml defaults
        assertThat(ProductionReadinessCheck.secretProblems(
                "change-me-to-a-random-string-of-at-least-32-bytes", "change-me-to-a-random-webhook-secret"))
                .hasSize(2);
        assertThat(ProductionReadinessCheck.secretProblems(
                "test-secret-key-that-is-at-least-32-bytes-long-for-hs256", STRONG)).hasSize(1);
        assertThat(ProductionReadinessCheck.secretProblems(STRONG, ProductionReadinessCheck.DEV_WEBHOOK_SECRET))
                .hasSize(1);
    }

    @Test
    void nullSecretsAreProblems() {
        assertThat(ProductionReadinessCheck.secretProblems(null, null)).hasSize(2);
    }
}
