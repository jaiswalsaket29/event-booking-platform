package org.saket.eventbooking.session.dto;

import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.enums.SessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A session as shown to clients. {@code ticketsAvailable} is uniform across modes: flat GA capacity,
 * the sum of tier capacities, or the number of AVAILABLE seats. {@code tiers} is empty unless TIERED.
 */
public record SessionResponse(
        UUID id,
        UUID eventId,
        LocationResponse location,
        UUID hallId,
        String hallName,
        Instant startTime,
        Instant endTime,
        SeatingType seatingType,
        PricingMode pricingMode,
        SessionStatus status,
        BigDecimal basePrice,
        int ticketsAvailable,
        List<TicketTierResponse> tiers) {
}
