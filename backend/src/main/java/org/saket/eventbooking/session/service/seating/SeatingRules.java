package org.saket.eventbooking.session.service.seating;

import org.saket.eventbooking.common.exception.BadRequestException;

/** Small request checks shared by the strategies. */
final class SeatingRules {

    private SeatingRules() {
    }

    static int requireQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BadRequestException("quantity is required and must be at least 1");
        }
        return quantity;
    }
}
