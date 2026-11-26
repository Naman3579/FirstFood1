package com.firstfood.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** F-06: the two knobs that stop unconfirmed requests from holding capacity. */
class SubscriptionPropertiesTest {

    @Test
    void validValuesAreKept() {
        SubscriptionProperties p = new SubscriptionProperties(Duration.ofHours(24), 2);
        assertThat(p.pendingTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(p.maxPendingPerAccount()).isEqualTo(2);
    }

    @Test
    void aTtlShorterThanAnHourIsRejectedAtStartup() {
        // A near-zero TTL would expire every request before staff could possibly see it.
        assertThatThrownBy(() -> new SubscriptionProperties(Duration.ofMinutes(59), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionProperties(Duration.ZERO, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCapMustAllowAtLeastOneRequest() {
        assertThatThrownBy(() -> new SubscriptionProperties(Duration.ofHours(48), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
