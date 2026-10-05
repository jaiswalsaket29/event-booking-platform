package org.saket.eventbooking.booking.event;

import java.util.UUID;

/** A CONFIRMED booking was cancelled because its session or event was cancelled; listeners act after commit. */
public record BookingCancelledByOrganiserEvent(UUID bookingId) {
}
