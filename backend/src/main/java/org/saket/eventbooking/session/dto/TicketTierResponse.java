package org.saket.eventbooking.session.dto;

import org.saket.eventbooking.session.entity.TicketTier;

import java.math.BigDecimal;
import java.util.UUID;

public record TicketTierResponse(UUID id, String name, BigDecimal price, int totalCapacity, int availableCapacity) {

    public static TicketTierResponse from(TicketTier tier) {
        return new TicketTierResponse(tier.getId(), tier.getName(), tier.getPrice(),
                tier.getTotalCapacity(), tier.getAvailableCapacity());
    }
}
