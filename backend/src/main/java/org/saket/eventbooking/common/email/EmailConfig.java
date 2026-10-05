package org.saket.eventbooking.common.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailConfig {

    /** Real delivery when SMTP is configured ({@code spring.mail.host}, from {@code MAIL_HOST}). */
    @Bean
    @ConditionalOnProperty(name = "spring.mail.host")
    public EmailService smtpEmailService(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        return new SmtpEmailService(mailSender, from);
    }

    /** Used unless another {@link EmailService} bean (e.g. SMTP above) is defined. */
    @Bean
    @ConditionalOnMissingBean(EmailService.class)
    public EmailService loggingEmailService() {
        return new LoggingEmailService();
    }
}
