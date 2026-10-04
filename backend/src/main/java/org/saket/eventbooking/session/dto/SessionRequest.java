package org.saket.eventbooking.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creates a session. Which fields are required depends on the mode (checked in SessionService):
 * <ul>
 *   <li>ASSIGNED_SEATING: {@code hallId} + {@code basePrice}; no pricingMode, capacity or tiers.</li>
 *   <li>GENERAL_ADMISSION + FLAT: {@code availableCapacity} + {@code basePrice}; no hall or tiers.</li>
 *   <li>GENERAL_ADMISSION + TIERED: {@code tiers}; no hall or capacity. basePrice becomes the cheapest tier.</li>
 * </ul>
 */
public record SessionRequest(
        @NotNull UUID locationId,
        UUID hallId,
        @NotNull @Future Instant startTime,
        @NotNull Instant endTime,
        @NotNull SeatingType seatingType,
        PricingMode pricingMode,
        @Positive Integer availableCapacity,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal basePrice,
        @Size(max = 20) List<@Valid @NotNull TicketTierRequest> tiers) {
}
