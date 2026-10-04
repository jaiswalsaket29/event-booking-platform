package org.saket.eventbooking.location.repository;

import org.saket.eventbooking.location.entity.Location;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findByCityIgnoreCase(String city, Sort sort);

    @Query("select distinct l.city from Location l order by l.city")
    List<String> findDistinctCities();
}
