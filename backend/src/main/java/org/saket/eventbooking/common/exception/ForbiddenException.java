package org.saket.eventbooking.common.exception;

/** Authenticated, but not allowed to do this yet (403), e.g. booking before verifying the email. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
