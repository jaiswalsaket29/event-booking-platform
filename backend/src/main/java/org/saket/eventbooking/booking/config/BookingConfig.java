package org.saket.eventbooking.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling // StaleHoldSweeper
@EnableConfigurationProperties(BookingProperties.class)
public class BookingConfig {
}
