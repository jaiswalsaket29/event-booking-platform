package org.saket.eventbooking.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param paymentId          our payment id, passed as the merchant reference (comes back in webhooks)
 * @param paymentMethodToken token from the provider's client-side widget; we never see card data
 * @param idempotencyKey     forwarded so the provider also de-duplicates retries of the same attempt
 */
public record ChargeRequest(UUID paymentId, BigDecimal amount, String currency, String paymentMethodToken,
                            String idempotencyKey) {
}
