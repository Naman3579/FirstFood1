package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SmsPropertiesTest {

    @Test
    void emptyAllowListFallsBackToIndiaRatherThanAllowingEverything() {
        assertThat(new SmsProperties(null, 500, 150, 80).allowedCountryCodes()).containsExactly("91");
        assertThat(new SmsProperties(List.of(), 500, 150, 80).allowedCountryCodes()).containsExactly("91");
    }

    @Test
    void countryCodesAreCleaned() {
        assertThat(new SmsProperties(List.of(" +91 ", "1"), 500, 150, 80).allowedCountryCodes())
                .containsExactly("91", "1");
    }

    @Test
    void junkIsRejectedAtStartup() {
        assertThatThrownBy(() -> new SmsProperties(List.of("0091"), 500, 150, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmsProperties(List.of("abc"), 500, 150, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmsProperties(List.of("91"), 0, 150, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmsProperties(List.of("91"), 500, 0, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmsProperties(List.of("91"), 500, 150, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmsProperties(List.of("91"), 500, 150, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
