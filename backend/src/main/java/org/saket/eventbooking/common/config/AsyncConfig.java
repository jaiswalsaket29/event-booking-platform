package org.saket.eventbooking.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables {@code @Async} (runs on Boot's auto-configured {@code applicationTaskExecutor}). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
