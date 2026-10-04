package org.saket.eventbooking.event.repository;

import org.saket.eventbooking.event.entity.Event;
import org.saket.eventbooking.event.enums.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

    @Query("select distinct e.category from Event e where e.status = :status order by e.category")
    List<String> findDistinctCategories(@Param("status") EventStatus status);
}
