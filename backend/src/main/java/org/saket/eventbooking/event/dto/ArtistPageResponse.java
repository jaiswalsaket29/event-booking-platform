package org.saket.eventbooking.event.dto;

import java.util.List;

/** Public artist page: profile plus their upcoming published events. */
public record ArtistPageResponse(ArtistResponse artist, List<EventSummaryResponse> upcomingEvents) {
}
