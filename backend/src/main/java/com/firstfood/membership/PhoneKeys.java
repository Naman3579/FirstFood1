package com.firstfood.membership;

import com.firstfood.identity.dto.PhonePattern;

/**
 * The one canonical form a phone number takes as an invitation key: spaces and hyphens removed, always with a leading
 * '+'. Accounts are stored in that same form since F-13 (V27), so keys and accounts compare directly.
 */
final class PhoneKeys {

    private PhoneKeys() {
    }

    /** The canonical '+'-prefixed form, or null when {@code raw} is not a plausible phone number. */
    static String canonical(String raw) {
        return PhonePattern.canonical(raw);
    }
}
