package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.PhonePattern;
import org.junit.jupiter.api.Test;

/** Audit F-13: one stored spelling for a phone number. */
class PhonePatternTest {

    @Test
    void addPlusGivesABareNumberItsPlusAndLeavesEverythingElseAlone() {
        assertThat(PhonePattern.addPlus("919876543210")).isEqualTo("+919876543210");
        assertThat(PhonePattern.addPlus("+919876543210")).isEqualTo("+919876543210");
        assertThat(PhonePattern.addPlus(null)).isNull();
        // not bare digits: untouched, so validation still rejects them exactly as before
        assertThat(PhonePattern.addPlus("a@b.com")).isEqualTo("a@b.com");
        assertThat(PhonePattern.addPlus("91 98765 43210")).isEqualTo("91 98765 43210");
        assertThat(PhonePattern.addPlus("0919876543210")).isEqualTo("0919876543210");
        assertThat(PhonePattern.addPlus("12345")).isEqualTo("12345");
        assertThat(PhonePattern.addPlus("")).isEmpty();
    }

    @Test
    void addPlusIsIdempotent() {
        String once = PhonePattern.addPlus("919876543210");
        assertThat(PhonePattern.addPlus(once)).isEqualTo(once);
    }

    @Test
    void canonicalStripsSpacesAndHyphensAndAlwaysHasAPlus() {
        assertThat(PhonePattern.canonical("+91 98765-43210")).isEqualTo("+919876543210");
        assertThat(PhonePattern.canonical("919876543210")).isEqualTo("+919876543210");
        assertThat(PhonePattern.canonical("+919876543210")).isEqualTo("+919876543210");
    }

    @Test
    void canonicalIsNullForAnythingThatIsNotAPhoneNumber() {
        assertThat(PhonePattern.canonical(null)).isNull();
        assertThat(PhonePattern.canonical("not-a-phone")).isNull();
        assertThat(PhonePattern.canonical("")).isNull();
        assertThat(PhonePattern.canonical("+0919876543210")).isNull();
        assertThat(PhonePattern.canonical("12345")).isNull();
    }

    @Test
    void theLoginRequestsCarryTheCanonicalNumber() {
        assertThat(new OtpRequestRequest("919876543210").phone()).isEqualTo("+919876543210");
    }
}
