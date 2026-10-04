package org.saket.eventbooking.payment.gateway;

/**
 * The payment provider. The default implementation is {@link SimulatedPaymentGateway}, so the app runs
 * with no third-party credentials; a real provider (e.g. Razorpay test mode) would be another bean.
 * <p>
 * A charge may finish synchronously (SUCCEEDED / FAILED) or report PROCESSING and deliver the outcome
 * later through the signed webhook, which is the source of truth either way.
 */
public interface PaymentGateway {

    ChargeResult charge(ChargeRequest request);
}
