package org.saket.eventbooking.support;

import org.saket.eventbooking.common.email.EmailService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test double that keeps sent emails in memory so tests can pull tokens out of the links. */
public class RecordingEmailService implements EmailService {

    /** {@code thread} lets tests check that @Async sending really happened off the caller's thread. */
    public record SentEmail(String to, String subject, String body, String thread) {}

    private static final Pattern TOKEN_PARAM = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private final List<SentEmail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new SentEmail(to, subject, body, Thread.currentThread().getName()));
    }

    public Optional<SentEmail> lastTo(String to) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).to().equals(to)) {
                return Optional.of(sent.get(i));
            }
        }
        return Optional.empty();
    }

    public long countTo(String to) {
        return sent.stream().filter(e -> e.to().equals(to)).count();
    }

    /** The {@code token} query parameter of the most recent link emailed to {@code to}. */
    public String lastTokenSentTo(String to) {
        SentEmail email = lastTo(to).orElseThrow(() -> new AssertionError("No email sent to " + to));
        Matcher matcher = TOKEN_PARAM.matcher(email.body());
        if (!matcher.find()) {
            throw new AssertionError("No token link in email: " + email.body());
        }
        return matcher.group(1);
    }
}
