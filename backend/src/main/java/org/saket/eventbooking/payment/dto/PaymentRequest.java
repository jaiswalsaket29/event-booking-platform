package org.saket.eventbooking.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param paymentMethodToken the token the provider's client widget produced. With the simulated
 *                           gateway: {@code tok_success}, {@code tok_decline}, {@code tok_insufficient_funds}.
 */
public record PaymentRequest(@NotBlank @Size(max = 100) String paymentMethodToken) {
}
