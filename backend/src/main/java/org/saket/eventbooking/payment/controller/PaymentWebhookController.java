package org.saket.eventbooking.payment.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.payment.service.PaymentAttemptService;
import org.saket.eventbooking.payment.webhook.PaymentWebhookService;
import org.saket.eventbooking.payment.webhook.WebhookSignature;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Called by the payment provider, not by users: no JWT, authenticated by the HMAC signature instead.
 * The body is taken as a raw String so the signature is checked over the exact bytes received.
 */
@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService webhookService;

    @PostMapping
    public Map<String, String> receive(@RequestBody(required = false) String body,
                                       @RequestHeader(value = WebhookSignature.HEADER, required = false) String signature) {
        PaymentAttemptService.Applied applied = webhookService.handle(body, signature);
        return Map.of("result", applied.name());
    }
}
