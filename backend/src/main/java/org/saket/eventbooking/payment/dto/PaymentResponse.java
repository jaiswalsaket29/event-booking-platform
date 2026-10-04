package org.saket.eventbooking.payment.dto;

import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment attempt, plus where its booking stands, so a checkout screen can show the result
 * (or keep polling while the payment is PENDING).
 */
public record PaymentResponse(
        UUID id,
        UUID bookingId,
        PaymentStatus status,
        BigDecimal amount,
        String transactionId,
        String failureReason,
        Instant createdAt,
        Instant paidAt,
        BookingStatus bookingStatus,
        String bookingReference) {

    public static PaymentResponse from(Payment payment) {
        var booking = payment.getBooking();
        return new PaymentResponse(payment.getId(), booking.getId(), payment.getStatus(), payment.getAmount(),
                payment.getTransactionId(), payment.getFailureReason(), payment.getCreatedAt(), payment.getPaidAt(),
                booking.getStatus(), booking.getBookingReference());
    }
}
