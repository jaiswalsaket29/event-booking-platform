package org.saket.eventbooking.location.dto;

import org.saket.eventbooking.location.entity.Hall;

import java.util.UUID;

public record HallResponse(UUID id, UUID locationId, String name, int totalCapacity) {

    public static HallResponse from(Hall hall) {
        return new HallResponse(hall.getId(), hall.getLocation().getId(), hall.getName(), hall.getTotalCapacity());
    }
}
