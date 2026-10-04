package org.saket.eventbooking.event.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.event.dto.ArtistRequest;
import org.saket.eventbooking.event.dto.ArtistResponse;
import org.saket.eventbooking.event.entity.Artist;
import org.saket.eventbooking.event.repository.ArtistRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArtistService {

    private final ArtistRepository artistRepository;

    @Transactional(readOnly = true)
    public PageResponse<ArtistResponse> list(String query, Pageable pageable) {
        var page = query == null || query.isBlank()
                ? artistRepository.findAll(pageable)
                : artistRepository.findByNameContainingIgnoreCase(query.trim(), pageable);
        return PageResponse.of(page, ArtistResponse::from);
    }

    @Transactional(readOnly = true)
    public ArtistResponse get(UUID id) {
        return ArtistResponse.from(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Artist getEntity(UUID id) {
        return artistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artist", id));
    }

    @Transactional
    public ArtistResponse create(ArtistRequest request) {
        Artist artist = new Artist();
        apply(artist, request);
        return ArtistResponse.from(artistRepository.save(artist));
    }

    @Transactional
    public ArtistResponse update(UUID id, ArtistRequest request) {
        Artist artist = getEntity(id);
        apply(artist, request);
        return ArtistResponse.from(artist);
    }

    @Transactional
    public void delete(UUID id) {
        Artist artist = getEntity(id);
        try {
            artistRepository.delete(artist);
            artistRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Artist is part of an event line-up; remove them from those events first");
        }
    }

    private static void apply(Artist artist, ArtistRequest request) {
        artist.setName(request.name().trim());
        artist.setBio(request.bio());
        artist.setImageUrl(request.imageUrl());
        artist.setGenre(request.genre());
    }
}
