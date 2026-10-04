package org.saket.eventbooking.event.dto;

import org.saket.eventbooking.event.entity.Event;
import org.saket.eventbooking.event.enums.EventStatus;

import java.util.UUID;

public record EventResponse(
        UUID id,
        String title,
        String description,
        String category,
        String imageUrl,
        EventStatus status,
        String language,
        Integer durationMinutes,
        String ageRestriction,
        String highlights,
        String termsAndConditions,
        boolean featured) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getTitle(), event.getDescription(), event.getCategory(),
                event.getImageUrl(), event.getStatus(), event.getLanguage(), event.getDurationMinutes(),
                event.getAgeRestriction(), event.getHighlights(), event.getTermsAndConditions(), event.isFeatured());
    }
}
