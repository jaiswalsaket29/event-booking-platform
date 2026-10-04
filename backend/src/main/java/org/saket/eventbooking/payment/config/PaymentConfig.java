package org.saket.eventbooking.payment.config;

import org.saket.eventbooking.payment.gateway.PaymentGateway;
import org.saket.eventbooking.payment.gateway.SimulatedPaymentGateway;
import org.saket.eventbooking.payment.gateway.SimulatedWebhookSender;
import org.saket.eventbooking.payment.webhook.PaymentWebhookService;
import org.saket.eventbooking.payment.webhook.WebhookSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    /** Used unless a real provider's {@link PaymentGateway} bean is defined. */
    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    public PaymentGateway simulatedPaymentGateway(PaymentProperties properties, TaskScheduler taskScheduler,
                                                  PaymentWebhookService webhookService, WebhookSignature signature,
                                                  ObjectMapper objectMapper) {
        PaymentProperties.Simulated simulated = properties.simulated();
        if (simulated.mode() == PaymentProperties.Simulated.Mode.SYNC) {
            return new SimulatedPaymentGateway();
        }
        return new SimulatedPaymentGateway(new SimulatedWebhookSender(
                taskScheduler, webhookService, signature, objectMapper, simulated.webhookDelay()));
    }
}
