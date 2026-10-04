package org.saket.eventbooking.event.dto;

import org.saket.eventbooking.session.dto.SessionResponse;

import java.util.List;

/** Public event page: details, line-up, gallery, and upcoming sessions. */
public record PublicEventResponse(
        EventResponse event,
        List<EventDetailResponse.EventArtistResponse> artists,
        List<EventDetailResponse.EventImageResponse> images,
        List<SessionResponse> sessions) {
}
