package org.saket.eventbooking.session.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.event.enums.EventStatus;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.saket.eventbooking.session.repository.SessionRepository;
import org.saket.eventbooking.session.service.seating.Hold;
import org.saket.eventbooking.session.service.seating.HoldRequest;
import org.saket.eventbooking.session.service.seating.HeldInventory;
import org.saket.eventbooking.session.service.seating.SeatingStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The session domain's entry point for selling inventory. The booking domain calls this service
 * (never the session repositories); it picks the {@link SeatingStrategy} for the session's mode.
 * <p>
 * {@code Propagation.MANDATORY}: holds and releases only make sense inside the caller's transaction,
 * because the row locks and the booking row must commit or roll back together.
 */
@Service
@RequiredArgsConstructor
public class SessionInventoryService {

    private final SessionRepository sessionRepository;
    private final List<SeatingStrategy> strategies;

    @Transactional(propagation = Propagation.MANDATORY)
    public Hold hold(UUID sessionId, HoldRequest request) {
        Session session = sessionRepository.findWithDetailsById(sessionId)
                .filter(s -> s.getEvent().getStatus() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
        if (session.getStatus() != SessionStatus.SCHEDULED) {
            throw new ConflictException("This session is " + session.getStatus().name().toLowerCase() + " and can't be booked");
        }
        if (!session.getStartTime().isAfter(Instant.now())) {
            throw new ConflictException("This session has already started");
        }
        return strategyFor(session).hold(session, request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(HeldInventory request) {
        Session session = sessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new ResourceNotFoundException("Session", request.sessionId()));
        strategyFor(session).release(session, request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(HeldInventory held) {
        Session session = sessionRepository.findById(held.sessionId())
                .orElseThrow(() -> new ResourceNotFoundException("Session", held.sessionId()));
        strategyFor(session).confirm(session, held);
    }

    /** Exactly one strategy must claim a session; session creation guarantees the modes don't mix. */
    SeatingStrategy strategyFor(Session session) {
        List<SeatingStrategy> matches = strategies.stream().filter(s -> s.supports(session)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one seating strategy for session " + session.getId()
                    + " (" + session.getSeatingType() + "/" + session.getPricingMode() + "), found " + matches.size());
        }
        return matches.getFirst();
    }
}
