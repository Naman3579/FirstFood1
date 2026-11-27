package com.firstfood.subscription;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionVerificationRepository extends JpaRepository<SubscriptionVerification, UUID> {

    List<SubscriptionVerification> findBySubscriptionIdIn(Collection<UUID> subscriptionIds);

    /**
     * F-06: how many requests this customer account is still waiting on: PENDING, not yet past the TTL, and the
     * subscription still ACTIVE (one staff already cancelled is not "waiting"). Requests past the TTL no longer count,
     * so the per-account cap cannot be held shut by ones the expiry job has not closed yet.
     */
    @Query("""
            select count(v) from SubscriptionVerification v, Subscription s
            where s.id = v.subscriptionId
              and s.status = com.firstfood.subscription.SubscriptionStatus.ACTIVE
              and v.requestedByAccountId = :accountId
              and v.status = com.firstfood.subscription.VerificationStatus.PENDING
              and v.requestedAt > :pendingCutoff
            """)
    long countFreshPendingByRequester(@Param("accountId") UUID accountId,
            @Param("pendingCutoff") java.time.Instant pendingCutoff);

    /**
     * F-06 expiry scan: PENDING requests requested at or before the cutoff, keyset-paged by subscription id. A candidate
     * list only (backed by idx_subscription_verification_pending); the job re-checks each one under its row locks.
     */
    @Query("""
            select new com.firstfood.subscription.SubscriptionKey(v.providerId, v.subscriptionId)
            from SubscriptionVerification v
            where v.status = com.firstfood.subscription.VerificationStatus.PENDING
              and v.requestedAt <= :pendingCutoff
              and v.subscriptionId > :afterId
            order by v.subscriptionId
            """)
    List<SubscriptionKey> findStalePendingKeys(@Param("pendingCutoff") java.time.Instant pendingCutoff,
            @Param("afterId") UUID afterId, org.springframework.data.domain.Pageable page);

    /** Row lock; the caller already holds the provider and subscription locks, this one orders concurrent decisions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from SubscriptionVerification v where v.subscriptionId = :id and v.providerId = :providerId")
    java.util.Optional<SubscriptionVerification> findForDecision(
            @Param("id") UUID subscriptionId, @Param("providerId") UUID providerId);
}
