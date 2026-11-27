package com.firstfood.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F-06: the new EXPIRED outcome of a subscription request, and what it must never be confused with. */
class SubscriptionVerificationExpiryTest {

    private static final Instant REQUESTED = Instant.parse("2026-10-01T10:00:00Z");

    private SubscriptionVerification pending() {
        return SubscriptionVerification.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), REQUESTED);
    }

    @Test
    void expiringAPendingRequestIsASystemDecisionWithNoStaffMemberAndNoReason() {
        SubscriptionVerification v = pending();
        Instant at = REQUESTED.plus(Duration.ofHours(49));

        v.expire(at);

        assertThat(v.getStatus()).isEqualTo(VerificationStatus.EXPIRED);
        assertThat(v.getDecidedAt()).isEqualTo(at);
        assertThat(v.getDecidedByAccountId()).isNull();
        assertThat(v.getRejectionReason()).isNull();
        assertThat(v.isPending()).isFalse();
    }

    @Test
    void anExpiredRequestCanNeverBeConfirmedRejectedOrExpiredAgain() {
        SubscriptionVerification v = pending();
        v.expire(REQUESTED.plusSeconds(1));

        assertThatThrownBy(() -> v.verify(UUID.randomUUID(), REQUESTED.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> v.reject(UUID.randomUUID(), "late", REQUESTED.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> v.expire(REQUESTED.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConfirmedRequestCanNeverBeExpired() {
        SubscriptionVerification v = pending();
        v.verify(UUID.randomUUID(), REQUESTED.plusSeconds(1));

        assertThatThrownBy(() -> v.expire(REQUESTED.plus(Duration.ofDays(30)))).isInstanceOf(IllegalStateException.class);
        assertThat(v.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void onlyAPendingRequestShowsWhenItLapses() {
        SubscriptionVerification v = pending();
        Duration ttl = Duration.ofHours(48);
        assertThat(VerificationView.from(v, ttl).expiresAt()).isEqualTo(REQUESTED.plus(ttl));

        v.expire(REQUESTED.plus(ttl));
        assertThat(VerificationView.from(v, ttl).expiresAt()).isNull();
        assertThat(VerificationView.from(null, ttl)).isNull();
    }
}
