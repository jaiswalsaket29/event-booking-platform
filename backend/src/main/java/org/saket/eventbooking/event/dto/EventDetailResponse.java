package org.saket.eventbooking.event.dto;

import java.util.List;
import java.util.UUID;

/** An event with its line-up and gallery. */
public record EventDetailResponse(
        EventResponse event,
        List<EventArtistResponse> artists,
        List<EventImageResponse> images) {

    public record EventArtistResponse(UUID artistId, String name, String imageUrl, String genre, String role) {
    }

    public record EventImageResponse(UUID id, String imageUrl, int sortOrder) {
    }
}
