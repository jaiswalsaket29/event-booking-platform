package org.saket.eventbooking.auth.exception;

/** Google authenticated the user, but we refuse to map them to an account (e.g. unverified email, admin). */
public class OAuth2LoginRejectedException extends RuntimeException {
    public OAuth2LoginRejectedException(String message) {
        super(message);
    }
}
