package org.saket.eventbooking.event.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.event.dto.EventDetailResponse;
import org.saket.eventbooking.event.dto.EventSearchCriteria;
import org.saket.eventbooking.event.dto.EventSummaryResponse;
import org.saket.eventbooking.event.dto.PublicEventResponse;
import org.saket.eventbooking.event.service.EventService;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.service.SessionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public, read-only event catalog. Only PUBLISHED events are visible. */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final SessionService sessionService;

    /**
     * Paged event list. {@code from}/{@code to} are ISO-8601 instants (the client converts local
     * dates to day boundaries); {@code sort} is DATE (soonest first, default) or TITLE.
     */
    @GetMapping
    public PageResponse<EventSummaryResponse> search(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) UUID artistId,
            @RequestParam(defaultValue = "DATE") EventSearchCriteria.Sort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
        EventSearchCriteria criteria = new EventSearchCriteria(city, category, from, to, q, featured, artistId, sort);
        return eventService.searchPublished(criteria, PageRequest.of(page, size));
    }

    /** Categories that have at least one published event, for "browse by category". */
    @GetMapping("/categories")
    public List<String> categories() {
        return eventService.listPublishedCategories();
    }

    @GetMapping("/{id}")
    public PublicEventResponse get(@PathVariable UUID id) {
        EventDetailResponse detail = eventService.getPublishedDetail(id);
        List<SessionResponse> sessions = sessionService.listUpcomingForPublishedEvent(id);
        return new PublicEventResponse(detail.event(), detail.artists(), detail.images(), sessions);
    }

    @GetMapping("/{id}/sessions")
    public List<SessionResponse> sessions(@PathVariable UUID id) {
        return sessionService.listUpcomingForPublishedEvent(id);
    }
}
