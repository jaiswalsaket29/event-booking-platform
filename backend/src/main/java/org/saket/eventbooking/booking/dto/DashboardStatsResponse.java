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
     * @param paymentsNeedingRefund     all-time count of successful charges whose booking isn't confirmed
     *                                  (paid too late, or the show was cancelled); refunds happen outside the app
     * @param stuckPendingPayments      all-time count of attempts still PENDING although their booking
     *                                  ended (no outcome ever arrived; check with the provider)
     */
    public record Totals(long confirmedBookings, long ticketsSold, BigDecimal revenue, long paymentsNeedingRefund,
                         long stuckPendingPayments) {
    }

    public record Day(LocalDate date, long confirmedBookings, long ticketsSold, BigDecimal revenue) {
    }

    public record TopEvent(UUID eventId, String title, long confirmedBookings, long ticketsSold, BigDecimal revenue) {
    }
}
