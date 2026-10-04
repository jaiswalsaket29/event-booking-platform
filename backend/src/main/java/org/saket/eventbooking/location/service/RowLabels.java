package org.saket.eventbooking.location.service;

import java.util.Comparator;

/** Spreadsheet-style row labels: 0 -> A, 25 -> Z, 26 -> AA, 27 -> AB, ... */
public final class RowLabels {

    /** Orders labels the way they're generated: shorter first, then alphabetically (Z before AA). */
    public static final Comparator<String> ORDER =
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private RowLabels() {
    }

    public static String of(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Row index must be non-negative: " + index);
        }
        StringBuilder label = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            n--;
            label.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return label.toString();
    }
}
