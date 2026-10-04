package org.saket.eventbooking.payment;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.payment.gateway.ChargeRequest;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.gateway.SimulatedPaymentGateway;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    private ChargeResult charge(String token, String key) {
        return gateway.charge(new ChargeRequest(UUID.randomUUID(), new BigDecimal("999.00"), "INR", token, key));
    }

    @Test
    void outcomesAreDeterminedByTheTestToken() {
        assertThat(charge("tok_success", "k1").status()).isEqualTo(ChargeResult.Status.SUCCEEDED);

        ChargeResult declined = charge("tok_decline", "k2");
        assertThat(declined.status()).isEqualTo(ChargeResult.Status.FAILED);
        assertThat(declined.failureReason()).isEqualTo("Card declined");

        assertThat(charge("tok_insufficient_funds", "k3").failureReason()).isEqualTo("Insufficient funds");
        assertThat(charge("4111111111111111", "k4").status()).isEqualTo(ChargeResult.Status.FAILED);
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalCharge() {
        ChargeResult first = charge("tok_success", "same-key");
        ChargeResult retry = charge("tok_decline", "same-key");
        assertThat(retry).isEqualTo(first);
        assertThat(charge("tok_success", "other-key").transactionId()).isNotEqualTo(first.transactionId());
    }

    @Test
    void transactionIdsLookLikeProviderIds() {
        assertThat(charge("tok_success", "k5").transactionId()).startsWith("sim_txn_").hasSize(40);
    }
}
