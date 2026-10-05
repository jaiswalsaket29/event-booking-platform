package org.saket.eventbooking.common.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.BackendApplication;
import org.saket.eventbooking.user.service.AdminPasswordBootstrap;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application with only the {@code prod} profile and environment-style settings, the way
 * a container would run it: proves application-prod.yml resolves, the app serves health, and the
 * readiness check refuses published dev secrets. Uses its own containers (no Spring test context).
 */
class ProdProfileStartupTest {

    private static final String STRONG_JWT = "q8Zr2N5vXw0pL7tY3mB9cK6hF1jD4sGa-jwt";
    private static final String STRONG_WEBHOOK = "Hk3vP9sW2qL7mN4xB8cT1zR6yF0dJ5aG-webhook";

    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> redis;

    @BeforeAll
    static void startContainers() {
        postgres = new PostgreSQLContainer("postgres:16");
        redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
        postgres.start();
        redis.start();
    }

    @AfterAll
    static void stopContainers() {
        redis.stop();
        postgres.stop();
    }

    private static Map<String, Object> prodEnvironment() {
        Map<String, Object> env = new HashMap<>();
        env.put("DB_HOST", postgres.getHost());
        env.put("DB_PORT", postgres.getMappedPort(5432));
        env.put("DB_NAME", postgres.getDatabaseName());
        env.put("DB_USER", postgres.getUsername());
        env.put("DB_PASSWORD", postgres.getPassword());
        env.put("REDIS_URL", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        env.put("JWT_SECRET", STRONG_JWT);
        env.put("PAYMENT_WEBHOOK_SECRET", STRONG_WEBHOOK);
        env.put("ADMIN_PASSWORD", "a-long-production-password");
        env.put("CORS_ALLOWED_ORIGINS", "https://events.example.test");
        env.put("FRONTEND_USER_URL", "https://events.example.test");
        env.put("PORT", 0);
        return env;
    }

    /** Settings go in as command-line args, the highest precedence, so a local backend/.env can't override them. */
    private static ConfigurableApplicationContext start(Map<String, Object> env) {
        String[] args = env.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("prod")
                .run(args);
    }

    /** The innermost exception that stopped startup. */
    private static Throwable startupFailure(Map<String, Object> env) {
        try (ConfigurableApplicationContext app = start(env)) {
            throw new AssertionError("expected startup to fail");
        } catch (RuntimeException e) {
            return NestedExceptionUtils.getMostSpecificCause(e);
        }
    }

    @Test
    void startsWithProductionSettingsAndServesHealthOnly() throws Exception {
        try (ConfigurableApplicationContext app = start(prodEnvironment())) {
            Environment environment = app.getEnvironment();
            String port = environment.getProperty("local.server.port");
            HttpClient http = HttpClient.newHttpClient();

            var health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"UP\"");

            var docs = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(docs.statusCode()).as("API docs are off in prod").isEqualTo(404);

            assertThat(app.getBean(AdminPasswordBootstrap.class).adminStillUsesDevPassword()).isFalse();
            assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        }
    }

    @Test
    void refusesToStartWithTheDevWebhookSecret() {
        Map<String, Object> env = prodEnvironment();
        env.put("PAYMENT_WEBHOOK_SECRET", ProductionReadinessCheck.DEV_WEBHOOK_SECRET);
        assertThat(startupFailure(env))
                .hasMessageContaining("Refusing to start with the prod profile")
                .hasMessageContaining("PAYMENT_WEBHOOK_SECRET");
    }

    @Test
    void refusesToStartWithoutRequiredSecrets() {
        Map<String, Object> env = prodEnvironment();
        env.remove("ADMIN_PASSWORD");
        assertThat(startupFailure(env)).hasMessageContaining("ADMIN_PASSWORD");
    }
}
