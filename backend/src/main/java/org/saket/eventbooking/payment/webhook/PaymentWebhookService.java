package org.saket.eventbooking.payment.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.repository.PaymentRepository;
import org.saket.eventbooking.payment.service.PaymentAttemptService;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The webhook is the source of truth for payment outcomes. Order of checks: signature over the raw
 * bytes first (nothing unauthenticated is even parsed), then parse, then match the payment, then apply
 * through {@link PaymentAttemptService#applyOutcome}, which is idempotent: a repeated delivery of the
 * same {@code transactionId} finds the payment already terminal and does nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final WebhookSignature signature;
    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptService attemptService;

    public PaymentAttemptService.Applied handle(String rawBody, String signatureHeader) {
        byte[] body = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        if (!signature.isValid(body, signatureHeader)) {
            throw new InvalidWebhookSignatureException();
        }

        PaymentWebhookEvent event;
        try {
            event = objectMapper.readValue(body, PaymentWebhookEvent.class);
        } catch (JacksonException e) {
            throw new BadRequestException("Malformed webhook payload");
        }
        ChargeResult.Status status = switch (event.type() == null ? "" : event.type()) {
            case PaymentWebhookEvent.SUCCEEDED -> ChargeResult.Status.SUCCEEDED;
            case PaymentWebhookEvent.FAILED -> ChargeResult.Status.FAILED;
            default -> throw new BadRequestException("Unsupported webhook type: " + event.type());
        };
        if (event.transactionId() == null || event.transactionId().isBlank()) {
            throw new BadRequestException("transactionId is required");
        }

        UUID paymentId = paymentRepository.findIdByTransactionId(event.transactionId())
                .or(() -> event.paymentId() != null && paymentRepository.existsById(event.paymentId())
                        ? java.util.Optional.of(event.paymentId())
                        : java.util.Optional.empty())
                // 404 makes a real provider retry later, which covers a webhook racing our own commit.
                .orElseThrow(() -> new ResourceNotFoundException("No payment for transaction " + event.transactionId()));

        PaymentAttemptService.Applied applied = attemptService.applyOutcome(paymentId, event.transactionId(), status,
                event.failureReason(), event.amount());
        log.info("Webhook {} ({}) for payment {}: {}", event.eventId(), event.type(), paymentId, applied);
        return applied;
    }
}
