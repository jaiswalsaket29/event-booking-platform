package org.saket.eventbooking.booking.dto;

import org.saket.eventbooking.booking.enums.BookingStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin booking search. All optional. {@code q} matches a booking reference exactly or a customer email
 * partially; {@code from}/{@code to} bound the booking's creation time.
 */
public record AdminBookingFilter(BookingStatus status, UUID eventId, UUID sessionId, String q,
                                 Instant from, Instant to) {
}
