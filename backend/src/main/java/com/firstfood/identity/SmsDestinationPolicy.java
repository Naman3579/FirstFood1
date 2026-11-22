package com.firstfood.identity;

import com.firstfood.identity.error.UnsupportedPhoneRegionException;
import org.springframework.stereotype.Component;

/**
 * Decides which phone numbers an OTP SMS may be sent to (F-05). The generic phone pattern ({@link
 * com.firstfood.identity.dto.PhonePattern}) accepts any 10-15 digit number from any country, which let an
 * unauthenticated caller aim SMS at premium or international numbers they profit from.
 *
 * <p>A number is allowed only when it is written WITH its country calling code (leading '+' optional on input; since
 * F-13 accounts are stored with it) and that code is on the allow-list. A bare 10-digit number is refused: without a country
 * code the SMS provider would have to guess the destination. India (91) is checked more tightly: exactly 10 national
 * digits starting 6-9 (Indian mobile numbering), which also rejects landline and premium ranges.
 *
 * <p>This is a pure function of the number: it never looks at accounts, so the answer cannot reveal whether a number
 * is registered.
 */
@Component
public class SmsDestinationPolicy {

    private static final String INDIA = "91";

    private final SmsProperties properties;

    public SmsDestinationPolicy(SmsProperties properties) {
        this.properties = properties;
    }

    public boolean isAllowed(String phone) {
        if (phone == null) {
            return false;
        }
        String digits = phone.trim();
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        for (String code : properties.allowedCountryCodes()) {
            if (digits.startsWith(code) && nationalPartLooksValid(code, digits.substring(code.length()))) {
                return true;
            }
        }
        return false;
    }

    /** Throws 400 {@code PHONE_REGION_NOT_SUPPORTED} unless {@link #isAllowed}. */
    public void assertAllowed(String phone) {
        if (!isAllowed(phone)) {
            throw new UnsupportedPhoneRegionException();
        }
    }

    private static boolean nationalPartLooksValid(String countryCode, String national) {
        if (INDIA.equals(countryCode)) {
            return national.length() == 10 && national.charAt(0) >= '6';
        }
        // Other countries: the generic pattern already bounds the total to 10-15 digits; require a plausible length.
        return national.length() >= 6 && national.length() <= 12;
    }
}
