package org.saket.eventbooking.common.exception;

/** A business-rule violation in an otherwise well-formed request (400). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
