package com.firstfood.provideraccess;

import com.firstfood.identity.dto.PhonePattern;

/** The one canonical form a phone takes as an offer key: no spaces/hyphens, always a leading '+'. */
final class OfferPhones {

    private OfferPhones() {
    }

    /** The canonical '+'-prefixed form, or null when {@code raw} is not a plausible phone number. */
    static String canonical(String raw) {
        return PhonePattern.canonical(raw);
    }

    /** "******1234": the last four digits only. */
    static String hint(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "******" + phone.substring(phone.length() - 4);
    }
}
