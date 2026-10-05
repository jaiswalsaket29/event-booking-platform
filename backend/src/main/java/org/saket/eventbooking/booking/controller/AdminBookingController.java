package org.saket.eventbooking.booking.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.booking.dto.AdminBookingFilter;
import org.saket.eventbooking.booking.dto.AdminBookingResponse;
import org.saket.eventbooking.booking.dto.DashboardStatsResponse;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.booking.service.BookingStatsService;
import org.saket.eventbooking.common.dto.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBookingController {

    private final BookingService bookingService;
    private final BookingStatsService bookingStatsService;

    /**
     * All bookings, newest first. Filters: {@code status}, {@code eventId}, {@code sessionId},
     * {@code q} (exact booking reference or part of the customer's email), {@code from}/{@code to}
     * (ISO instants on creation time), {@code needsAttention=true} (late or stuck payments).
     */
    @GetMapping("/bookings")
    public PageResponse<AdminBookingResponse> list(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "false") boolean needsAttention,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return bookingService.adminSearch(new AdminBookingFilter(status, eventId, sessionId, q, from, to, needsAttention), pageable);
    }

    @GetMapping("/bookings/{id}")
    public AdminBookingResponse get(@PathVariable UUID id) {
        return bookingService.adminGet(id);
    }

    /**
     * Confirmed bookings, tickets and revenue per day plus top events, for calendar days
     * {@code from}..{@code to} (inclusive, business time zone). Defaults to the last 30 days.
     */
    @GetMapping("/dashboard/stats")
    public DashboardStatsResponse stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return bookingStatsService.stats(from, to);
    }
}
