package org.saket.eventbooking.booking.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.booking.dto.DashboardStatsResponse;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.repository.BookingSeatRepository;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.payment.service.PaymentQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Revenue and booking counts for the admin dashboard, by confirmation date in the business time zone.
 * <p>
 * Rows are fetched with one query and grouped by day in Java. That keeps "which day is it?" in one
 * place ({@link ZoneId} in {@code app.reporting.zone}) instead of in database time-zone settings, and
 * is fine at this scale (a year of a busy venue is tens of thousands of rows). At larger scale this
 * would become a SQL {@code GROUP BY} over a {@code timestamptz} column or a pre-aggregated table.
 */
@Service
@RequiredArgsConstructor
public class BookingStatsService {

    static final int MAX_RANGE_DAYS = 366;
    static final int TOP_EVENTS = 5;

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PaymentQueryService paymentQueryService;

    @Value("${app.reporting.zone:Asia/Kolkata}")
    private ZoneId zone;

    private record Row(UUID bookingId, LocalDate day, BigDecimal amount, long tickets, UUID eventId, String title) {}

    @Transactional(readOnly = true)
    public DashboardStatsResponse stats(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(29);
        if (end.isBefore(start)) {
            throw new BadRequestException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BadRequestException("Date range can be at most " + MAX_RANGE_DAYS + " days");
        }

        Instant fromInstant = start.atStartOfDay(zone).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(zone).toInstant(); // inclusive end day
        List<Object[]> raw = bookingRepository.findConfirmedBetween(fromInstant, toInstant);

        // tickets = GA quantity, or the number of seats for assigned seating (one grouped query)
        List<UUID> seatedIds = raw.stream().filter(r -> r[3] == null).map(r -> (UUID) r[0]).toList();
        Map<UUID, Long> seatCounts = new HashMap<>();
        if (!seatedIds.isEmpty()) {
            for (Object[] row : bookingSeatRepository.countByBookingIds(seatedIds)) {
                seatCounts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        List<Row> rows = raw.stream().map(r -> new Row(
                (UUID) r[0],
                LocalDate.ofInstant((Instant) r[1], zone),
                (BigDecimal) r[2],
                r[3] != null ? ((Number) r[3]).longValue() : seatCounts.getOrDefault((UUID) r[0], 0L),
                (UUID) r[4],
                (String) r[5])).toList();

        Map<LocalDate, List<Row>> byDay = new HashMap<>();
        rows.forEach(r -> byDay.computeIfAbsent(r.day(), d -> new ArrayList<>()).add(r));
        List<DashboardStatsResponse.Day> daily = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<Row> dayRows = byDay.getOrDefault(day, List.of());
            daily.add(new DashboardStatsResponse.Day(day, dayRows.size(), tickets(dayRows), revenue(dayRows)));
        }

        Map<UUID, List<Row>> byEvent = new LinkedHashMap<>();
        rows.forEach(r -> byEvent.computeIfAbsent(r.eventId(), e -> new ArrayList<>()).add(r));
        List<DashboardStatsResponse.TopEvent> topEvents = byEvent.values().stream()
                .map(eventRows -> new DashboardStatsResponse.TopEvent(eventRows.getFirst().eventId(),
                        eventRows.getFirst().title(), eventRows.size(), tickets(eventRows), revenue(eventRows)))
                .sorted(Comparator.comparing(DashboardStatsResponse.TopEvent::revenue).reversed()
                        .thenComparing(DashboardStatsResponse.TopEvent::title))
                .limit(TOP_EVENTS)
                .toList();

        DashboardStatsResponse.Totals totals = new DashboardStatsResponse.Totals(rows.size(), tickets(rows),
                revenue(rows), paymentQueryService.countLatePayments());
        return new DashboardStatsResponse(start, end, zone.getId(), totals, daily, topEvents);
    }

    private static long tickets(List<Row> rows) {
        return rows.stream().mapToLong(Row::tickets).sum();
    }

    private static BigDecimal revenue(List<Row> rows) {
        return rows.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
