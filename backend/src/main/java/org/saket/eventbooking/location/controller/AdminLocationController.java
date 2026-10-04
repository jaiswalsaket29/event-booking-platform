package org.saket.eventbooking.location.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.location.dto.HallRequest;
import org.saket.eventbooking.location.dto.HallResponse;
import org.saket.eventbooking.location.dto.LocationRequest;
import org.saket.eventbooking.location.dto.LocationResponse;
import org.saket.eventbooking.location.service.HallService;
import org.saket.eventbooking.location.service.LocationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/locations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminLocationController {

    private final LocationService locationService;
    private final HallService hallService;

    @GetMapping
    public List<LocationResponse> list(@RequestParam(required = false) String city) {
        return locationService.list(city);
    }

    @GetMapping("/{id}")
    public LocationResponse get(@PathVariable UUID id) {
        return locationService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse create(@Valid @RequestBody LocationRequest request) {
        return locationService.create(request);
    }

    @PutMapping("/{id}")
    public LocationResponse update(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        return locationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        locationService.delete(id);
    }

    @GetMapping("/{id}/halls")
    public List<HallResponse> listHalls(@PathVariable UUID id) {
        return hallService.listByLocation(id);
    }

    @PostMapping("/{id}/halls")
    @ResponseStatus(HttpStatus.CREATED)
    public HallResponse createHall(@PathVariable UUID id, @Valid @RequestBody HallRequest request) {
        return hallService.create(id, request);
    }
}
