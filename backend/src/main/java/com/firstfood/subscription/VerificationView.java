package com.firstfood.subscription;

import java.time.Duration;
import java.time.Instant;

/**
 * Where a subscription stands with the provider's staff. Embedded in both staff and customer views.
 *
 * @param expiresAt F-06: for a PENDING request, when it lapses if nobody confirms it ({@code requestedAt + pending-ttl});
 *                  null once it has been decided. Informational: the job and the capacity count make the real decision.
 */
public record VerificationView(VerificationStatus status, Instant requestedAt, Instant decidedAt,
        String rejectionReason, Instant expiresAt) {

    static VerificationView from(SubscriptionVerification v, Duration pendingTtl) {
        if (v == null) {
            return null;
        }
        Instant expiresAt = v.isPending() ? v.getRequestedAt().plus(pendingTtl) : null;
        return new VerificationView(v.getStatus(), v.getRequestedAt(), v.getDecidedAt(), v.getRejectionReason(),
                expiresAt);
    }
}
