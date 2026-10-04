package org.saket.eventbooking.booking.dto;

import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.location.enums.SeatType;
import org.saket.eventbooking.session.enums.SeatingType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A booking as its owner sees it. {@code holdExpiresAt} is set only while PENDING: the checkout must
 * be paid before then or the held tickets go back on sale. {@code bookingReference} stays null until
 * the booking is CONFIRMED (Phase 5).
 */
public record BookingResponse(
        UUID id,
        BookingStatus status,
        String bookingReference,
        UUID sessionId,
        UUID eventId,
        String eventTitle,
        Instant sessionStart,
        String venueName,
        String city,
        String hallName,
        SeatingType seatingType,
        TierInfo ticketTier,
        int ticketCount,
        List<SeatInfo> seats,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant holdExpiresAt) {

    public record TierInfo(UUID id, String name, BigDecimal price) {
    }

    public record SeatInfo(UUID sessionSeatId, String rowLabel, int seatNumber, SeatType seatType, BigDecimal price) {
    }
}
