package org.saket.eventbooking.location.repository;

import org.saket.eventbooking.location.entity.Hall;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HallRepository extends JpaRepository<Hall, UUID> {

    List<Hall> findByLocationIdOrderByName(UUID locationId);

    @EntityGraph(attributePaths = "location")
    Optional<Hall> findWithLocationById(UUID id);
}
