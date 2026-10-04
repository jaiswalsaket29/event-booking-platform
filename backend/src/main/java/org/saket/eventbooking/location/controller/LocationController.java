package org.saket.eventbooking.location.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.location.service.LocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Public, read-only venue data. */
@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    public List<LocationResponse> list(@RequestParam(required = false) String city) {
        return locationService.list(city);
    }

    /** Distinct cities, for the frontend's city picker. */
    @GetMapping("/cities")
    public List<String> cities() {
        return locationService.listCities();
    }

    @GetMapping("/{id}")
    public LocationResponse get(@PathVariable UUID id) {
        return locationService.get(id);
    }
}
