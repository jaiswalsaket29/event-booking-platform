package org.saket.eventbooking.session.config;

import org.saket.eventbooking.location.enums.SeatType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Seat-type pricing table for assigned seating: a session's seat price is
 * {@code basePrice x multiplier(seatType)}, frozen into {@code SessionSeat.price} at session creation.
 */
@ConfigurationProperties(prefix = "app.pricing")
public record SeatPricingProperties(Map<SeatType, BigDecimal> seatTypeMultipliers) {
}
