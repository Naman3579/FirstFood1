package com.firstfood.identity;

import java.util.Locale;
import java.util.Optional;

/**
 * The two doors into the product. One account (one phone number) can be registered on either or both:
 * CUSTOMER = the customer portal at "/", STAFF = the provider-staff portal at "/staff".
 *
 * <p>A portal is a DOOR, NOT A SECURITY BOUNDARY (audit F-12). Anyone with a phone that can receive an OTP can register
 * on either portal, including staff, so "is registered on STAFF" says nothing about trust. It only keeps the two UIs'
 * APIs apart (see {@code StaffPortalInterceptor} / {@code CustomerPortalInterceptor}). Anything sensitive must be
 * decided by what the caller may do to THAT resource: the per-provider role check, ownership, membership, or a
 * step-up OTP. Never grant, widen or skip a check because an account has (or lacks) a portal.
 */
public enum Portal {
    CUSTOMER,
    STAFF;

    /** Parses the URL form ("customer" / "staff", any case). */
    public static Optional<Portal> fromPath(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
