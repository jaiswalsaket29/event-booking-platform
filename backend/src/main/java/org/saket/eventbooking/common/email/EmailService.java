package org.saket.eventbooking.common.email;

/**
 * Outbound email. The default implementation ({@link LoggingEmailService}) only logs, so the app runs
 * locally without SMTP credentials; a real SMTP implementation can be added behind this interface.
 */
public interface EmailService {

    void send(String to, String subject, String body);
}
