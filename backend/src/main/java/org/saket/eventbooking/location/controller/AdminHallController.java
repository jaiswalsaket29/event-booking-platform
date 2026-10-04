package org.saket.eventbooking.location.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.location.dto.HallDetailResponse;
import org.saket.eventbooking.location.dto.HallRequest;
import org.saket.eventbooking.location.dto.HallResponse;
import org.saket.eventbooking.location.dto.SeatLayoutRequest;
import org.saket.eventbooking.location.service.HallService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/halls")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminHallController {

    private final HallService hallService;

    @GetMapping("/{id}")
    public HallDetailResponse get(@PathVariable UUID id) {
        return hallService.getDetail(id);
    }

    @PutMapping("/{id}")
    public HallResponse update(@PathVariable UUID id, @Valid @RequestBody HallRequest request) {
        return hallService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        hallService.delete(id);
    }

    /** Bulk seat layout: replaces all seats of the hall (rows x seats per row x seat type, per block). */
    @PutMapping("/{id}/seats")
    public HallDetailResponse replaceLayout(@PathVariable UUID id, @Valid @RequestBody SeatLayoutRequest request) {
        return hallService.replaceLayout(id, request);
    }
}
