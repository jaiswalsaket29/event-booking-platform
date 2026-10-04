package org.saket.eventbooking.user.exception;

import org.saket.eventbooking.common.exception.BadRequestException;

/** An email-verification or password-reset token that is unknown, expired, or already used (400). */
public class InvalidTokenException extends BadRequestException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
