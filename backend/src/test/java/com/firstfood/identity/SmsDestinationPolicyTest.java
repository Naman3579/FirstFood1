package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.identity.error.UnsupportedPhoneRegionException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F-05: which numbers an OTP SMS may be sent to. */
class SmsDestinationPolicyTest {

    private final SmsDestinationPolicy india = new SmsDestinationPolicy(new SmsProperties(List.of("91"), 500, 150, 80));

    @Test
    void indianMobileNumbersAreAllowedWithOrWithoutThePlus() {
        assertThat(india.isAllowed("+919876543210")).isTrue();
        assertThat(india.isAllowed("919876543210")).isTrue();
        assertThat(india.isAllowed("+916000000000")).isTrue();
    }

    @Test
    void internationalNumbersAreRefused() {
        assertThat(india.isAllowed("+14155552671")).isFalse();   // US
        assertThat(india.isAllowed("+442071838750")).isFalse();  // UK
        assertThat(india.isAllowed("+8613812345678")).isFalse(); // China
        assertThat(india.isAllowed("+971501234567")).isFalse();  // UAE
        assertThat(india.isAllowed("+2521234567")).isFalse();    // a premium-rate-prone destination
    }

    @Test
    void aBareTenDigitNumberHasNoCountryCodeAndIsRefused() {
        assertThat(india.isAllowed("9876543210")).isFalse();
        // ...even one that happens to begin with the digits 91.
        assertThat(india.isAllowed("9112345678")).isFalse();
    }

    @Test
    void indianNumbersMustBeTenNationalDigitsStartingSixToNine() {
        assertThat(india.isAllowed("+91987654321")).isFalse();     // 9 national digits
        assertThat(india.isAllowed("+9198765432101")).isFalse();   // 11 national digits
        assertThat(india.isAllowed("+915876543210")).isFalse();    // starts with 5: not a mobile range
        assertThat(india.isAllowed("+911234567890")).isFalse();    // starts with 1
    }

    @Test
    void malformedInputIsRefusedNotThrown() {
        assertThat(india.isAllowed(null)).isFalse();
        assertThat(india.isAllowed("")).isFalse();
        assertThat(india.isAllowed("+")).isFalse();
        assertThat(india.isAllowed("+91 98765 43210")).isFalse(); // spaces never reach here (DTO pattern), refuse anyway
        assertThat(india.isAllowed("+91abc4567890")).isFalse();
    }

    @Test
    void assertAllowedThrowsTheDedicated400() {
        assertThatThrownBy(() -> india.assertAllowed("+14155552671"))
                .isInstanceOfSatisfying(UnsupportedPhoneRegionException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PHONE_REGION_NOT_SUPPORTED");
                    assertThat(e.getStatus().value()).isEqualTo(400);
                });
    }

    @Test
    void anotherCountryCanBeAddedByConfiguration() {
        SmsDestinationPolicy indiaAndUs = new SmsDestinationPolicy(new SmsProperties(List.of("91", "+1"), 500, 150, 80));
        assertThat(indiaAndUs.isAllowed("+14155552671")).isTrue();
        assertThat(indiaAndUs.isAllowed("+919876543210")).isTrue();
        assertThat(indiaAndUs.isAllowed("+442071838750")).isFalse();
    }
}
