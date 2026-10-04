package org.saket.eventbooking.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.payment.webhook.PaymentWebhookEvent;
import org.saket.eventbooking.payment.webhook.PaymentWebhookService;
import org.saket.eventbooking.payment.webhook.WebhookSignature;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Plays the provider's side of webhooks for {@link SimulatedPaymentGateway}: after a delay it signs a
 * JSON event with the shared secret and hands it to {@link PaymentWebhookService}, exactly the code
 * path {@code POST /api/v1/payments/webhook} uses (signature check included). Like real providers it
 * retries a few times if the payment isn't visible yet.
 */
@Slf4j
public class SimulatedWebhookSender {

    private static final int MAX_DELIVERIES = 3;

    private final TaskScheduler scheduler;
    private final PaymentWebhookService webhookService;
    private final WebhookSignature signature;
    private final ObjectMapper objectMapper;
    private final Duration delay;

    public SimulatedWebhookSender(TaskScheduler scheduler, PaymentWebhookService webhookService,
                                  WebhookSignature signature, ObjectMapper objectMapper, Duration delay) {
        this.scheduler = scheduler;
        this.webhookService = webhookService;
        this.signature = signature;
        this.objectMapper = objectMapper;
        this.delay = delay;
    }

    public void deliverLater(ChargeRequest request, ChargeResult outcome) {
        PaymentWebhookEvent event = new PaymentWebhookEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                outcome.status() == ChargeResult.Status.SUCCEEDED ? PaymentWebhookEvent.SUCCEEDED : PaymentWebhookEvent.FAILED,
                outcome.transactionId(), request.paymentId(), request.amount(), outcome.failureReason());
        schedule(event, 1);
    }

    private void schedule(PaymentWebhookEvent event, int delivery) {
        scheduler.schedule(() -> deliver(event, delivery), Instant.now().plus(delay));
    }

    private void deliver(PaymentWebhookEvent event, int delivery) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(event);
            webhookService.handle(new String(body, StandardCharsets.UTF_8), signature.sign(body));
        } catch (ResourceNotFoundException e) {
            if (delivery < MAX_DELIVERIES) {
                schedule(event, delivery + 1);
            } else {
                log.warn("Simulated webhook {} gave up after {} deliveries", event.eventId(), delivery);
            }
        } catch (RuntimeException e) {
            log.warn("Simulated webhook {} failed", event.eventId(), e);
        }
    }
}
