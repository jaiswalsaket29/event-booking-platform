package org.saket.eventbooking.booking.event;

import java.util.UUID;

/** Published inside the confirming transaction; listeners act after commit. */
public record BookingConfirmedEvent(UUID bookingId) {
}
