package org.saket.eventbooking.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.payment.dto.PaymentRequest;
import org.saket.eventbooking.payment.dto.PaymentResponse;
import org.saket.eventbooking.payment.service.PaymentQueryService;
import org.saket.eventbooking.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings/{bookingId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentQueryService paymentQueryService;
    private final BookingService bookingService;

    /**
     * Pays for a PENDING booking. {@code Idempotency-Key} is generated once per checkout screen;
     * 201 when this request created the attempt, 200 when it replays an earlier one with the same key.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@AuthenticationPrincipal UUID userId,
                                               @PathVariable UUID bookingId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody PaymentRequest request) {
        PaymentService.PayResult result = paymentService.pay(userId, bookingId, idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.payment());
    }

    /** All attempts for one of the caller's bookings (also what a checkout screen polls). */
    @GetMapping
    public List<PaymentResponse> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID bookingId) {
        bookingService.getMine(userId, bookingId); // 404 unless it's theirs
        return paymentQueryService.listForBookings(List.of(bookingId)).getOrDefault(bookingId, List.of());
    }
}
