package org.saket.eventbooking.payment.gateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayMemoryTest {

    private static ChargeRequest request(String key) {
        return new ChargeRequest(UUID.randomUUID(), BigDecimal.TEN, "INR", "tok_success", key);
    }

    @Test
    void remembersAtMostTheConfiguredNumberOfCharges() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(null, 3);
        for (int i = 0; i < 50; i++) {
            gateway.charge(request("key-" + i));
        }
        assertThat(gateway.rememberedCharges()).isEqualTo(3);
    }

    @Test
    void recentlyUsedKeysSurviveEviction() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(null, 2);
        ChargeResult first = gateway.charge(request("a"));
        gateway.charge(request("b"));
        gateway.charge(request("a")); // touch "a" so "b" is now least recently used
        gateway.charge(request("c")); // evicts "b"

        assertThat(gateway.charge(request("a"))).isEqualTo(first);
        assertThat(gateway.rememberedCharges()).isEqualTo(2);
    }
}
