package org.saket.eventbooking.session.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.session.dto.SeatMapResponse;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.session.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public session reads (sessions of unpublished events are 404). */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable UUID id) {
        return sessionService.getPublic(id);
    }

    /** Seat map for assigned seating: status and price per seat. 400 for general admission. */
    @GetMapping("/{id}/seats")
    public SeatMapResponse seats(@PathVariable UUID id) {
        return sessionService.publicSeatMap(id);
    }
}
