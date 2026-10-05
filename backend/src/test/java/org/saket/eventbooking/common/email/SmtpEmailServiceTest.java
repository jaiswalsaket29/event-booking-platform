package org.saket.eventbooking.common.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SmtpEmailServiceTest {

    /** Captures messages instead of connecting to a server. */
    static class CapturingSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent = new ArrayList<>();
        boolean fail;

        @Override
        public void send(SimpleMailMessage... messages) {
            if (fail) {
                throw new MailSendException("SMTP server unavailable");
            }
            sent.addAll(List.of(messages));
        }
    }

    @Test
    void sendsAPlainTextMessageFromTheConfiguredAddress() {
        CapturingSender sender = new CapturingSender();
        new SmtpEmailService(sender, "Event Booking <no-reply@events.example.test>")
                .send("fan@example.test", "Verify your email", "Open this link: https://events.example.test/v?t=abc");

        assertThat(sender.sent).singleElement().satisfies(m -> {
            assertThat(m.getFrom()).isEqualTo("Event Booking <no-reply@events.example.test>");
            assertThat(m.getTo()).containsExactly("fan@example.test");
            assertThat(m.getSubject()).isEqualTo("Verify your email");
            assertThat(m.getText()).contains("https://events.example.test/v?t=abc");
        });
    }

    @Test
    void smtpIsUsedOnlyWhenAMailHostIsConfigured() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(EmailConfig.class)
                .withBean(JavaMailSender.class, CapturingSender::new)
                .withPropertyValues("app.mail.from=no-reply@events.example.test");
        runner.run(ctx -> assertThat(ctx.getBean(EmailService.class)).isInstanceOf(LoggingEmailService.class));
        runner.withPropertyValues("spring.mail.host=smtp.example.test")
                .run(ctx -> assertThat(ctx.getBean(EmailService.class)).isInstanceOf(SmtpEmailService.class));
    }

    @Test
    void aMailFailureIsLoggedNotThrown() {
        CapturingSender sender = new CapturingSender();
        sender.fail = true;
        assertThatCode(() -> new SmtpEmailService(sender, "no-reply@events.example.test")
                .send("fan@example.test", "Reset your password", "link"))
                .doesNotThrowAnyException();
    }
}
