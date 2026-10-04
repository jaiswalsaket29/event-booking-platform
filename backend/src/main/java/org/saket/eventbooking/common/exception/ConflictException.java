package org.saket.eventbooking.common.exception;

/** The request is valid but clashes with the current state of a resource (409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
