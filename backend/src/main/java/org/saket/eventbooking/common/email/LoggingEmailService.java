package org.saket.eventbooking.common.email;

import lombok.extern.slf4j.Slf4j;

/** Dev/fallback implementation: writes the email (including any links) to the application log. */
@Slf4j
public class LoggingEmailService implements EmailService {

    @Override
    public void send(String to, String subject, String body) {
        log.info("""

                ---- EMAIL (dev, not sent) ----
                To: {}
                Subject: {}

                {}
                -------------------------------""", to, subject, body);
    }
}
