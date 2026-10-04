package org.saket.eventbooking.session.repository;

import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.enums.SessionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    @EntityGraph(attributePaths = {"location", "hall"})
    List<Session> findByEventIdOrderByStartTime(UUID eventId);

    @EntityGraph(attributePaths = {"location", "hall"})
    List<Session> findByEventIdAndStatusAndStartTimeAfterOrderByStartTime(
            UUID eventId, SessionStatus status, Instant after);

    @EntityGraph(attributePaths = {"event", "location", "hall"})
    Optional<Session> findWithDetailsById(UUID id);
}
