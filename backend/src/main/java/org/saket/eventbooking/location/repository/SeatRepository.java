package org.saket.eventbooking.location.repository;

import org.saket.eventbooking.location.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByHallId(UUID hallId);

    long countByHallId(UUID hallId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Seat s where s.hall.id = :hallId")
    int deleteByHallId(@Param("hallId") UUID hallId);
}
