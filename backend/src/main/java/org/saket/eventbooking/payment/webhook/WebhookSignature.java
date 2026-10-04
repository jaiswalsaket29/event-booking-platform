package org.saket.eventbooking.payment.webhook;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.payment.config.PaymentProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * {@code X-Webhook-Signature: sha256=<hex HMAC-SHA256 of the raw request body>} with the shared
 * {@code app.payments.webhook-secret}. The same scheme most providers use (Razorpay, Stripe, GitHub).
 */
@Component
@RequiredArgsConstructor
public class WebhookSignature {

    public static final String HEADER = "X-Webhook-Signature";
    private static final String PREFIX = "sha256=";

    private final PaymentProperties properties;

    public String sign(byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmac(body));
    }

    /**
     * Constant-time comparison ({@link MessageDigest#isEqual}), so response timing doesn't reveal how
     * many leading characters of a forged signature were right.
     */
    public boolean isValid(byte[] body, String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = header.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
