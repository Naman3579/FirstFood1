package com.firstfood.identity.dto;

import java.util.Locale;

/** One definition of "an email we will send a code to" and of its canonical (trimmed, lower-cased) form. */
public final class EmailPattern {

    /** Deliberately simple: something@something.tld with no spaces. The emailed code is the real proof. */
    public static final String REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private EmailPattern() {
    }

    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
