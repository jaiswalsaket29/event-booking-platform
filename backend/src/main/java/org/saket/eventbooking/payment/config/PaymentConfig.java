package org.saket.eventbooking.payment.config;

import org.saket.eventbooking.payment.gateway.PaymentGateway;
import org.saket.eventbooking.payment.gateway.SimulatedPaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    /** Used unless a real provider's {@link PaymentGateway} bean is defined. */
    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    public PaymentGateway simulatedPaymentGateway() {
        return new SimulatedPaymentGateway();
    }
}
