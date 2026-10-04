package org.saket.eventbooking.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.booking.dto.BookingResponse;
import org.saket.eventbooking.booking.dto.CreateBookingRequest;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.booking.service.BookingExpiryService;
import org.saket.eventbooking.booking.service.BookingService;
import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;
import org.saket.eventbooking.payment.gateway.ChargeResult;
import org.saket.eventbooking.payment.repository.PaymentRepository;
import org.saket.eventbooking.payment.service.PaymentAttemptService;
import org.saket.eventbooking.payment.webhook.WebhookSignature;
import org.saket.eventbooking.session.dto.SessionResponse;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.saket.eventbooking.support.CatalogFixtures.daysFromNow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The webhook endpoint, driven by hand-signed payloads against attempts that haven't been charged yet. */
class PaymentWebhookTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingExpiryService expiryService;
    @Autowired PaymentAttemptService attemptService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired WebhookSignature signature;

    private User user;
    private SessionResponse session;

    @BeforeEach
    void setUp() {
        user = createUser(Role.USER, true);
        UUID eventId = fixtures.publishedEvent(unique("Webhooked")).id();
        session = fixtures.flatSession(eventId, fixtures.location(unique("Hooksville")).id(), daysFromNow(5), 10, "600");
    }

    /** A PENDING booking with a PENDING payment attempt (as if the gateway answered PROCESSING). */
    private PaymentAttemptService.OpenedAttempt pendingAttempt() {
        BookingResponse booking = bookingService.create(user.getId(), new CreateBookingRequest(session.id(), null, 2, null));
        return attemptService.open(user.getId(), booking.id(), UUID.randomUUID().toString());
    }

    private static String event(String type, String txn, UUID paymentId, String amount, String reason) {
        return """
                {"eventId": "evt_%s", "type": "%s", "transactionId": "%s", "paymentId": "%s", "amount": %s, "failureReason": %s}
                """.formatted(UUID.randomUUID(), type, txn, paymentId, amount, reason == null ? "null" : "\"" + reason + "\"");
    }

    private ResultActions send(String body) throws Exception {
        return send(body, signature.sign(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions send(String body, String sig) throws Exception {
        var request = post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
        if (sig != null) {
            request = request.header(WebhookSignature.HEADER, sig);
        }
        return mockMvc.perform(request);
    }

    private Payment payment(UUID id) {
        return paymentRepository.findById(id).orElseThrow();
    }

    private BookingStatus bookingStatusOf(UUID paymentId) {
        UUID bookingId = paymentRepository.findBookingIdById(paymentId).orElseThrow();
        return bookingRepository.findById(bookingId).orElseThrow().getStatus();
    }

    @Test
    void signedSuccessWebhookConfirmsAndDuplicatesAreNoOps() throws Exception {
        PaymentAttemptService.OpenedAttempt attempt = pendingAttempt();
        String body = event("payment.succeeded", "txn_" + UUID.randomUUID(), attempt.paymentId(), "1200.00", null);

        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("APPLIED"));
        assertThat(payment(attempt.paymentId()).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(bookingStatusOf(attempt.paymentId())).isEqualTo(BookingStatus.CONFIRMED);

        // providers deliver at least once: the same event again changes nothing
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));
    }

    @Test
    void forgedOrMissingSignaturesAreRejectedBeforeParsing() throws Exception {
        PaymentAttemptService.OpenedAttempt attempt = pendingAttempt();
        String body = event("payment.succeeded", "txn_" + UUID.randomUUID(), attempt.paymentId(), "1200.00", null);

        send(body, null).andExpect(status().isUnauthorized());
        send(body, "sha256=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        // a valid signature for a different body doesn't transfer
        String tampered = body.replace("1200.00", "1.00");
        send(tampered, signature.sign(body.getBytes(StandardCharsets.UTF_8))).andExpect(status().isUnauthorized());
        send("not json at all", null).andExpect(status().isUnauthorized());

        assertThat(payment(attempt.paymentId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void malformedOrUnknownEventsAreRejected() throws Exception {
        send("{not json").andExpect(status().isBadRequest());
        send(event("payment.refunded", "txn_x", UUID.randomUUID(), "1", null)).andExpect(status().isBadRequest());
        // unknown payment: 404 so a real provider retries later
        send(event("payment.succeeded", "txn_" + UUID.randomUUID(), UUID.randomUUID(), "1", null))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedWebhookKeepsTheBookingOpenForARetry() throws Exception {
        PaymentAttemptService.OpenedAttempt attempt = pendingAttempt();
        send(event("payment.failed", "txn_" + UUID.randomUUID(), attempt.paymentId(), "1200.00", "Card declined"))
                .andExpect(status().isOk());
        assertThat(payment(attempt.paymentId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment(attempt.paymentId()).getFailureReason()).isEqualTo("Card declined");
        assertThat(bookingStatusOf(attempt.paymentId())).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void amountMismatchIsTreatedAsFailure() throws Exception {
        PaymentAttemptService.OpenedAttempt attempt = pendingAttempt();
        send(event("payment.succeeded", "txn_" + UUID.randomUUID(), attempt.paymentId(), "1.00", null))
                .andExpect(status().isOk());
        assertThat(payment(attempt.paymentId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment(attempt.paymentId()).getFailureReason()).isEqualTo("Amount mismatch");
        assertThat(bookingStatusOf(attempt.paymentId())).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void webhookBeforeTheSynchronousAnswerAndInEitherOrder() throws Exception {
        PaymentAttemptService.OpenedAttempt attempt = pendingAttempt();
        String txn = "txn_" + UUID.randomUUID();

        // webhook lands first, matched by our payment id, and records the transaction id
        send(event("payment.succeeded", txn, attempt.paymentId(), "1200.00", null)).andExpect(status().isOk());
        assertThat(payment(attempt.paymentId()).getTransactionId()).isEqualTo(txn);

        // then the gateway's synchronous "processing" answer arrives: harmless
        assertThat(attemptService.applyOutcome(attempt.paymentId(), txn, ChargeResult.Status.PROCESSING, null, null))
                .isEqualTo(PaymentAttemptService.Applied.DUPLICATE);
        // a different transaction id for the same payment is refused
        send(event("payment.succeeded", "txn_" + UUID.randomUUID(), attempt.paymentId(), "1200.00", null))
                .andExpect(status().isConflict());
        assertThat(bookingStatusOf(attempt.paymentId())).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void successAfterTheHoldExpiredIsRecordedAsALatePayment() throws Exception {
        PaymentAttemptService.OpenedAttempt attempt = pendingAttempt();
        UUID bookingId = paymentRepository.findBookingIdById(attempt.paymentId()).orElseThrow();
        expiryService.expire(bookingId); // hold ran out while the payment was in flight
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.FAILED);

        send(event("payment.succeeded", "txn_" + UUID.randomUUID(), attempt.paymentId(), "1200.00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("LATE_PAYMENT"));
        assertThat(payment(attempt.paymentId()).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.FAILED);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getBookingReference()).isNull();
    }

    @Test
    void signatureIsHexHmacAndCaseInsensitive() {
        String sig = signature.sign("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(sig).matches("sha256=[0-9a-f]{64}");
        assertThat(signature.isValid("{}".getBytes(StandardCharsets.UTF_8), sig.toUpperCase().replace("SHA256", "sha256"))).isTrue();
    }
}
