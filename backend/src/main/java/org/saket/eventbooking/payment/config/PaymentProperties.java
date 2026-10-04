package org.saket.eventbooking.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.payments.*}.
 *
 * @param maxAttempts   payment attempts allowed per booking before it FAILS (default 3)
 * @param currency      ISO currency (INR only, per design)
 * @param webhookSecret HMAC-SHA256 key for webhook signatures ({@code PAYMENT_WEBHOOK_SECRET})
 * @param simulated     settings for {@code SimulatedPaymentGateway}
 */
@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(int maxAttempts, String currency, String webhookSecret, Simulated simulated) {

    /**
     * @param mode         WEBHOOK: charges return PROCESSING and the outcome arrives as a signed webhook
     *                     after {@code webhookDelay} (realistic); SYNC: charges finish immediately
     * @param webhookDelay how long the simulated provider "takes" before sending the webhook
     */
    public record Simulated(Mode mode, Duration webhookDelay) {
        public enum Mode { WEBHOOK, SYNC }
    }
}
