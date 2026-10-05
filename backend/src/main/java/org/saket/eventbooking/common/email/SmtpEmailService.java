package org.saket.eventbooking.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends plain-text email over SMTP (any provider: Brevo, Mailgun, SES, Gmail...). Active when
 * {@code spring.mail.host} is set; otherwise {@link LoggingEmailService} is used.
 *
 * <p>Failures are logged, not thrown: verification and reset emails are sent inside the signup/reset
 * transaction, and a mail outage shouldn't fail a signup (the user can ask for the link again).
 */
@Slf4j
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailService(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("Could not send email '{}' via SMTP", subject, e);
        }
    }
}
