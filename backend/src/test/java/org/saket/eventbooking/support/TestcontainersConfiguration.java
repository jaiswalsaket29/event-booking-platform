package org.saket.eventbooking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres container per Spring test context. Every integration test shares the same
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

    @Bean
    @Primary
    RecordingEmailService recordingEmailService() {
        return new RecordingEmailService();
    }
}
