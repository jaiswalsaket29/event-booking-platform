package org.saket.eventbooking.location.dto;

import org.saket.eventbooking.location.entity.Location;
import org.saket.eventbooking.location.enums.VenueType;

import java.util.UUID;

public record LocationResponse(UUID id, String name, String address, String city, VenueType venueType) {

    public static LocationResponse from(Location location) {
        return new LocationResponse(location.getId(), location.getName(), location.getAddress(),
                location.getCity(), location.getVenueType());
    }
}
