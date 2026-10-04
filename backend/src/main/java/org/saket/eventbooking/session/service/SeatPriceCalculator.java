package org.saket.eventbooking.session.service;

import org.saket.eventbooking.location.enums.SeatType;
import org.saket.eventbooking.session.config.SeatPricingProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/** Turns a session's base price into a per-seat price using the configured seat-type multipliers. */
@Component
public class SeatPriceCalculator {

    private final Map<SeatType, BigDecimal> multipliers;

    public SeatPriceCalculator(SeatPricingProperties properties) {
        Map<SeatType, BigDecimal> configured = properties.seatTypeMultipliers();
        if (configured == null) {
            throw new IllegalStateException("app.pricing.seat-type-multipliers is not configured");
        }
        this.multipliers = new EnumMap<>(SeatType.class);
        for (SeatType type : SeatType.values()) {
            BigDecimal multiplier = configured.get(type);
            // Fail at startup rather than at the first session creation.
            if (multiplier == null || multiplier.signum() <= 0) {
                throw new IllegalStateException("Missing or non-positive price multiplier for seat type " + type);
            }
            multipliers.put(type, multiplier);
        }
    }

    public BigDecimal priceFor(SeatType seatType, BigDecimal basePrice) {
        return basePrice.multiply(multipliers.get(seatType)).setScale(2, RoundingMode.HALF_UP);
    }
}
