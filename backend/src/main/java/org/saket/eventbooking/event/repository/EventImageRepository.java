package org.saket.eventbooking.event.repository;

import org.saket.eventbooking.event.entity.EventImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventImageRepository extends JpaRepository<EventImage, UUID> {

    List<EventImage> findByEventIdOrderBySortOrderAscIdAsc(UUID eventId);

    Optional<EventImage> findByIdAndEventId(UUID id, UUID eventId);
}
