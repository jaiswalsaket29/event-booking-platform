package org.saket.eventbooking.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Admin dashboard numbers for confirmed bookings in [from, to] (calendar days in {@code zone}).
 * {@code daily} has an entry for every day in the range, zero-filled, so charts need no gap handling.
 */
public record DashboardStatsResponse(
        LocalDate from,
        LocalDate to,
        String zone,
        Totals totals,
        List<Day> daily,
        List<TopEvent> topEvents) {

    /**
     * @param latePaymentsNeedingRefund all-time count of successful charges whose booking had already
     *                                  ended (refunds are handled outside the app)
     */
    public record Totals(long confirmedBookings, long ticketsSold, BigDecimal revenue, long latePaymentsNeedingRefund) {
    }

    public record Day(LocalDate date, long confirmedBookings, long ticketsSold, BigDecimal revenue) {
    }

    public record TopEvent(UUID eventId, String title, long confirmedBookings, long ticketsSold, BigDecimal revenue) {
    }
}
