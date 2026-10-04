package org.saket.eventbooking.event.dto;

import org.saket.eventbooking.event.entity.Artist;

import java.util.UUID;

public record ArtistResponse(UUID id, String name, String bio, String imageUrl, String genre) {

    public static ArtistResponse from(Artist artist) {
        return new ArtistResponse(artist.getId(), artist.getName(), artist.getBio(),
                artist.getImageUrl(), artist.getGenre());
    }
}
