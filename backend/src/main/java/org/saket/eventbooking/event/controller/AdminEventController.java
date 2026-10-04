package org.saket.eventbooking.event.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.event.dto.EventArtistsRequest;
import org.saket.eventbooking.event.dto.EventDetailResponse;
import org.saket.eventbooking.event.dto.EventImageRequest;
import org.saket.eventbooking.event.dto.EventRequest;
import org.saket.eventbooking.event.dto.EventResponse;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.event.service.EventService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventService eventService;

    @GetMapping
    public PageResponse<EventResponse> list(@RequestParam(required = false) EventStatus status,
                                            @RequestParam(required = false) String q,
                                            @PageableDefault(size = 20, sort = "title", direction = Sort.Direction.ASC)
                                            Pageable pageable) {
        return eventService.listForAdmin(status, q, pageable);
    }

    @GetMapping("/{id}")
    public EventDetailResponse get(@PathVariable UUID id) {
        return eventService.getDetail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody EventRequest request) {
        return eventService.create(request);
    }

    @PutMapping("/{id}")
    public EventResponse update(@PathVariable UUID id, @Valid @RequestBody EventRequest request) {
        return eventService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        eventService.delete(id);
    }

    @PutMapping("/{id}/artists")
    public EventDetailResponse replaceArtists(@PathVariable UUID id, @Valid @RequestBody EventArtistsRequest request) {
        return eventService.replaceArtists(id, request);
    }

    @PostMapping("/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetailResponse addImage(@PathVariable UUID id, @Valid @RequestBody EventImageRequest request) {
        return eventService.addImage(id, request);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        eventService.deleteImage(id, imageId);
    }
}
