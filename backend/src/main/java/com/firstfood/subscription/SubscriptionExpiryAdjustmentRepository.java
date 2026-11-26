package com.firstfood.subscription;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence only. Rows are append-only (V25 trigger): there is no update or delete path. */
public interface SubscriptionExpiryAdjustmentRepository extends JpaRepository<SubscriptionExpiryAdjustment, UUID> {

    @Query("""
            select new com.firstfood.subscription.AdjustmentTotal(a.subscriptionId, sum(a.days))
            from SubscriptionExpiryAdjustment a
            where a.subscriptionId in :ids
            group by a.subscriptionId
            """)
    List<AdjustmentTotal> totalsFor(@Param("ids") Collection<UUID> subscriptionIds);

    List<SubscriptionExpiryAdjustment> findBySubscriptionIdAndKindOrderByCreatedAtAsc(
            UUID subscriptionId, AdjustmentKind kind);

    List<SubscriptionExpiryAdjustment> findBySubscriptionIdOrderByCreatedAtAsc(UUID subscriptionId);

    List<SubscriptionExpiryAdjustment> findByClosureIdOrderByCreatedAtAsc(UUID closureId);

    boolean existsByClosureIdAndSubscriptionId(UUID closureId, UUID subscriptionId);
}
