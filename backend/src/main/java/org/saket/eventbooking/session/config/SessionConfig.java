package org.saket.eventbooking.session.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SeatPricingProperties.class)
public class SessionConfig {
}
