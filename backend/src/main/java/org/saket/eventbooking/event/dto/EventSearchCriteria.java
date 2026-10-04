package org.saket.eventbooking.event.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public event-list filters. All optional. {@code from} defaults to now, so only events with an
 * upcoming scheduled session matching the filters are listed.
 */
public record EventSearchCriteria(
        String city,
        String category,
        Instant from,
        Instant to,
        String query,
        Boolean featured,
        UUID artistId,
        Sort sort) {

    public enum Sort {
        /** Soonest next matching session first. */
        DATE,
        TITLE
    }
}
