package org.saket.eventbooking.event.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event card for listings. {@code nextSessionStart} and {@code startingPrice} ("from ₹X") are computed
 * over the sessions that matched the search filters.
 */
public record EventSummaryResponse(
        UUID id,
        String title,
        String category,
        String imageUrl,
        String language,
        Integer durationMinutes,
        String ageRestriction,
        boolean featured,
        Instant nextSessionStart,
        BigDecimal startingPrice) {
}
