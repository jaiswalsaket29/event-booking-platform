package org.saket.eventbooking.event.repository;

import org.saket.eventbooking.event.entity.EventArtist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EventArtistRepository extends JpaRepository<EventArtist, UUID> {

    @Query("select ea from EventArtist ea join fetch ea.artist where ea.event.id = :eventId order by ea.artist.name")
    List<EventArtist> findByEventIdWithArtist(@Param("eventId") UUID eventId);

    @Modifying(flushAutomatically = true)
    @Query("delete from EventArtist ea where ea.event.id = :eventId")
    int deleteByEventId(@Param("eventId") UUID eventId);
}
