package org.saket.eventbooking.event.repository;

import org.saket.eventbooking.event.entity.Artist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ArtistRepository extends JpaRepository<Artist, UUID> {

    Page<Artist> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
