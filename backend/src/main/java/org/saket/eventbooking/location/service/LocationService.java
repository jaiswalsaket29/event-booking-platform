package org.saket.eventbooking.location.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.location.dto.LocationRequest;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.location.entity.Location;
import org.saket.eventbooking.location.repository.LocationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocationService {

    private static final Sort BY_CITY_THEN_NAME = Sort.by("city", "name");

    private final LocationRepository locationRepository;

    @Transactional(readOnly = true)
    public List<LocationResponse> list(String city) {
        List<Location> locations = city == null || city.isBlank()
                ? locationRepository.findAll(BY_CITY_THEN_NAME)
                : locationRepository.findByCityIgnoreCase(city.trim(), BY_CITY_THEN_NAME);
        return locations.stream().map(LocationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<String> listCities() {
        return locationRepository.findDistinctCities();
    }

    @Transactional(readOnly = true)
    public LocationResponse get(UUID id) {
        return LocationResponse.from(getEntity(id));
    }

    /** For other domains' services (e.g. session creation) that need the entity itself. */
    @Transactional(readOnly = true)
    public Location getEntity(UUID id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Location", id));
    }

    @Transactional
    public LocationResponse create(LocationRequest request) {
        Location location = new Location();
        apply(location, request);
        return LocationResponse.from(locationRepository.save(location));
    }

    @Transactional
    public LocationResponse update(UUID id, LocationRequest request) {
        Location location = getEntity(id);
        apply(location, request);
        return LocationResponse.from(location);
    }

    /** Fails with 409 (FK violation) while halls or sessions still reference the location. */
    @Transactional
    public void delete(UUID id) {
        locationRepository.delete(getEntity(id));
        locationRepository.flush();
    }

    private static void apply(Location location, LocationRequest request) {
        location.setName(request.name().trim());
        location.setAddress(request.address());
        location.setCity(request.city().trim());
        location.setVenueType(request.venueType());
    }
}
