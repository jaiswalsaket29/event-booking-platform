package org.saket.eventbooking.booking.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.service.BookingQrService;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.common.ratelimit.RateLimits;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The signed-in user's bookings. */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final BookingQrService bookingQrService;
    private final RateLimits rateLimits;

    /**
     * Holds the tickets for {@code app.booking.hold-ttl} and returns the PENDING booking.
     * Rate-limited per user: each hold takes inventory off sale, so hold-and-abandon loops are throttled.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@AuthenticationPrincipal UUID userId,
                                  @Valid @RequestBody CreateBookingRequest request) {
        rateLimits.bookingCreation(userId);
        return bookingService.create(userId, request);
    }

    /** My bookings, newest first. */
    @GetMapping
    public PageResponse<BookingResponse> listMine(@AuthenticationPrincipal UUID userId,
                                                  @PageableDefault(size = 20, sort = "createdAt",
                                                          direction = Sort.Direction.DESC) Pageable pageable) {
        return bookingService.listMine(userId, pageable);
    }

    /** 404 unless the booking belongs to the caller. */
    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return bookingService.getMine(userId, id);
    }

    /** Abandon a PENDING checkout and release the held tickets now. */
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return bookingService.cancelMine(userId, id);
    }

    /** The entry QR (PNG) for a CONFIRMED booking; it encodes the booking reference. */
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // it's an entry ticket; keep it out of shared caches
                .contentType(MediaType.IMAGE_PNG)
                .body(bookingQrService.qrPngForOwner(userId, id));
    }
}
