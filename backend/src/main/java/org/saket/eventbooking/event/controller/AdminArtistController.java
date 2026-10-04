package org.saket.eventbooking.event.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.event.dto.ArtistRequest;
import org.saket.eventbooking.event.dto.ArtistResponse;
import org.saket.eventbooking.event.service.ArtistService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/artists")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminArtistController {

    private final ArtistService artistService;

    @GetMapping
    public PageResponse<ArtistResponse> list(@RequestParam(required = false) String q,
                                             @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                             Pageable pageable) {
        return artistService.list(q, pageable);
    }

    @GetMapping("/{id}")
    public ArtistResponse get(@PathVariable UUID id) {
        return artistService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArtistResponse create(@Valid @RequestBody ArtistRequest request) {
        return artistService.create(request);
    }

    @PutMapping("/{id}")
    public ArtistResponse update(@PathVariable UUID id, @Valid @RequestBody ArtistRequest request) {
        return artistService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        artistService.delete(id);
    }
}
