package org.saket.eventbooking.booking.service;

import java.security.SecureRandom;

/**
 * Booking references like {@code EVT-7K3QH9M2XD4P}: 12 random characters from Crockford's base32
 * alphabet (no I, L, O, U, so nothing is misread at the door) = 60 bits of entropy. The QR code encodes
 * this value, so it must not be guessable; it is only meaningful together with a database lookup.
 */
public final class BookingReferenceGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingReferenceGenerator() {
    }

    public static String next() {
        StringBuilder reference = new StringBuilder("EVT-");
        for (int i = 0; i < LENGTH; i++) {
            reference.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return reference.toString();
    }
}
