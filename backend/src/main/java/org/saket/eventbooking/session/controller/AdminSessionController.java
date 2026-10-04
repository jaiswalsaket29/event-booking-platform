package org.saket.eventbooking.session.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.session.dto.SeatMapResponse;
import org.saket.eventbooking.session.dto.SessionRequest;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.dto.SessionUpdateRequest;
import org.saket.eventbooking.session.dto.TicketTierRequest;
import org.saket.eventbooking.session.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminSessionController {

    private final SessionService sessionService;

    @GetMapping("/events/{eventId}/sessions")
    public List<SessionResponse> listForEvent(@PathVariable UUID eventId) {
        return sessionService.listForEvent(eventId);
    }

    @PostMapping("/events/{eventId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@PathVariable UUID eventId, @Valid @RequestBody SessionRequest request) {
        return sessionService.create(eventId, request);
    }

    @GetMapping("/sessions/{id}")
    public SessionResponse get(@PathVariable UUID id) {
        return sessionService.get(id);
    }

    @PutMapping("/sessions/{id}")
    public SessionResponse update(@PathVariable UUID id, @Valid @RequestBody SessionUpdateRequest request) {
        return sessionService.update(id, request);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        sessionService.delete(id);
    }

    @GetMapping("/sessions/{id}/seats")
    public SeatMapResponse seatMap(@PathVariable UUID id) {
        return sessionService.seatMap(id);
    }

    @PostMapping("/sessions/{id}/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse addTier(@PathVariable UUID id, @Valid @RequestBody TicketTierRequest request) {
        return sessionService.addTier(id, request);
    }

    @PutMapping("/tiers/{tierId}")
    public SessionResponse updateTier(@PathVariable UUID tierId, @Valid @RequestBody TicketTierRequest request) {
        return sessionService.updateTier(tierId, request);
    }

    @DeleteMapping("/tiers/{tierId}")
    public SessionResponse deleteTier(@PathVariable UUID tierId) {
        return sessionService.deleteTier(tierId);
    }
}
