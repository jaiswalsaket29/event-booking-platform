package org.saket.eventbooking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres and one Redis container per Spring test context. Every integration test shares the same
 * context configuration, so the context (and this container) is cached and reused across classes.
 * Emails are captured in memory instead of being logged.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16");
    }

    /** Same flags as docker-compose: publish key-expired events so hold expiry can be tested end to end. */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7")
                .withExposedPorts(6379)
                .withCommand("redis-server", "--notify-keyspace-events", "Ex");
    }

    @Bean
    @Primary
    RecordingEmailService recordingEmailService() {
        return new RecordingEmailService();
    }
}
