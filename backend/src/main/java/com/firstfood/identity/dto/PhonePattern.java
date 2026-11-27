package com.firstfood.identity.dto;

import java.util.regex.Pattern;

/**
 * Loosely E.164-ish: optional leading +, 10-15 digits total, first digit
 * non-zero. Deliberately not stricter (e.g. India-only) since the pilot
 * numbers used for local testing may not always be Indian mobile
 * numbers, and full E.164 validation belongs in a dedicated library if
 * this needs to get precise later.
 *
 * <p>F-13: the ONE stored form of a phone number is "+" followed by the digits ("+919876543210"). "+91..." and "91..."
 * used to be two different strings, so one SIM could own two accounts. Everything that stores or looks up a phone
 * goes through {@link #addPlus} (login side) or {@link #canonical} (staff-typed numbers).
 */
public final class PhonePattern {

    public static final String REGEX = "^\\+?[1-9]\\d{9,14}$";

    private static final Pattern PHONE = Pattern.compile(REGEX);
    private static final Pattern BARE_DIGITS = Pattern.compile("^[1-9]\\d{9,14}$");

    private PhonePattern() {
    }

    /**
     * Login-side normalisation: a bare run of digits gets its leading '+'; anything else (already '+', null, an email,
     * garbage) is returned untouched so validation still rejects it exactly as before. Never strips spaces or hyphens.
     */
    public static String addPlus(String raw) {
        return raw != null && BARE_DIGITS.matcher(raw).matches() ? "+" + raw : raw;
    }

    /**
     * Staff-typed numbers (customer invitations, role assignment, ownership offers): spaces and hyphens removed, always
     * with a leading '+'. Null when {@code raw} is not a plausible phone number.
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("[\\s-]", "");
        if (!PHONE.matcher(compact).matches()) {
            return null;
        }
        return compact.startsWith("+") ? compact : "+" + compact;
    }
}
