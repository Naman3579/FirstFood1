package com.firstfood.subscription;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionLifecycleServiceImpl implements SubscriptionLifecycleService {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionVerificationRepository verifications;
    private final SubscriptionProperties properties;
    private final Clock clock;

    SubscriptionLifecycleServiceImpl(SubscriptionRepository subscriptions,
            SubscriptionVerificationRepository verifications, SubscriptionProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.verifications = verifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionKey> findLapsed(LocalDate today, UUID afterId, int limit) {
        return subscriptions.findLapsedKeys(today, afterId, PageRequest.of(0, requirePositive(limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionKey> findExtensionCandidates(LocalDate today, UUID afterId, int limit) {
        return subscriptions.findExtensionCandidateKeys(today, afterId, PageRequest.of(0, requirePositive(limit)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfLapsed(UUID subscriptionId, LocalDate today) {
        Subscription subscription = subscriptions.findById(subscriptionId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        if (!subscription.isLapsedOn(today)) {
            return false;
        }
        subscription.expire(clock.instant());
        subscriptions.saveAndFlush(subscription);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionKey> findStalePending(UUID afterId, int limit) {
        return verifications.findStalePendingKeys(stalePendingCutoff(), afterId, PageRequest.of(0, requirePositive(limit)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireStalePending(UUID providerId, UUID subscriptionId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // Lock order everywhere: subscription row, then verification row (the same as a staff rejection).
        Subscription subscription = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        SubscriptionVerification verification = verifications.findForDecision(subscriptionId, providerId).orElse(null);
        // Decided since the scan, or not old enough (the TTL was raised, or the scan raced a fresh request): leave it.
        if (verification == null || !verification.isPending()
                || verification.getRequestedAt().isAfter(now.minus(properties.pendingTtl()))) {
            return false;
        }
        verification.expire(now);
        verifications.saveAndFlush(verification);
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            subscription.cancel(verification.getRequestedByAccountId(),
                    "Cancelled automatically: the provider did not confirm it within "
                            + properties.pendingTtl().toHours() + " hours.", now);
            subscriptions.saveAndFlush(subscription);
        }
        return true;
    }

    private Instant stalePendingCutoff() {
        return clock.instant().minus(properties.pendingTtl());
    }

    private static int requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
