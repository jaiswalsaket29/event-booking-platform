package org.saket.eventbooking.event.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.event.dto.ArtistPageResponse;
import org.saket.eventbooking.event.dto.ArtistResponse;
import org.saket.eventbooking.event.dto.EventSearchCriteria;
import org.saket.eventbooking.event.service.ArtistService;
import org.saket.eventbooking.event.service.EventService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public artist directory and artist pages. */
@RestController
@RequestMapping("/api/v1/artists")
@RequiredArgsConstructor
public class ArtistController {

    private static final int ARTIST_PAGE_EVENT_LIMIT = 50;

    private final ArtistService artistService;
    private final EventService eventService;

    @GetMapping
    public PageResponse<ArtistResponse> list(@RequestParam(required = false) String q,
                                             @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                             Pageable pageable) {
        return artistService.list(q, pageable);
    }

    @GetMapping("/{id}")
    public ArtistPageResponse get(@PathVariable UUID id) {
        ArtistResponse artist = artistService.get(id);
        EventSearchCriteria upcoming = new EventSearchCriteria(null, null, null, null, null, null, id,
                EventSearchCriteria.Sort.DATE);
        var events = eventService.searchPublished(upcoming, PageRequest.of(0, ARTIST_PAGE_EVENT_LIMIT)).content();
        return new ArtistPageResponse(artist, events);
    }
}
