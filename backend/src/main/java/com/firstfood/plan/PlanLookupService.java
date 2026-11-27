package com.firstfood.plan;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Narrow lookup for other modules (subscription). Exists so they never touch {@link Plan},
 * {@link SubscriptionPolicy} or their repositories directly (rules.md Rule 27.3/27.4).
 * Performs NO authorization - callers must have called
 * {@link com.firstfood.provideraccess.ProviderAccessService#requirePermission} first.
 */
public interface PlanLookupService {

    /**
     * The plan as offered right now, with its current policy version. Scoped by provider: a plan
     * of another provider is simply absent (no IDOR). Inactive plans are returned too (see
     * {@link PlanOffer#active()}) so the caller can say why a sale is refused.
     *
     * Must run inside an existing transaction. Callers that sell the plan should already hold the
     * provider row lock: every plan edit takes it, so the plan and its policy version cannot change
     * between this read and the sale.
     */
    Optional<PlanOffer> findOffer(UUID providerId, UUID planId);

    /** The ACTIVE plans of a provider with their current policy, newest first (what a customer may subscribe to). */
    List<PlanListing> findActiveListings(UUID providerId);

    /** Active-plan count and price range per provider, for the given ids; providers without any are absent. */
    Map<UUID, PlanSummary> summarizeActive(Collection<UUID> providerIds);

    /**
     * Of the given providers, those with at least one ACTIVE plan priced within [{@code min}, {@code max}]
     * (either bound may be null = open).
     */
    Set<UUID> providersWithActivePlanPricedBetween(Collection<UUID> providerIds, BigDecimal min, BigDecimal max);
}
