package org.saket.eventbooking.booking.dto;

import org.saket.eventbooking.payment.dto.PaymentResponse;

import java.util.List;
import java.util.UUID;

/** A booking as admins see it: the customer's view plus who booked it and every payment attempt. */
public record AdminBookingResponse(
        BookingResponse booking,
        Customer customer,
        List<PaymentResponse> payments) {

    public record Customer(UUID id, String name, String email) {
    }
}
