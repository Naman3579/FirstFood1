package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * SYSTEM ONLY (V25): what the closure module may ask of the subscription module. Like {@link SubscriptionExpiryService}
 * it performs NO authorization - the caller must have authorized the actor for the provider, taken the provider row lock
 * and recorded the closure. No controller may depend on it.
 *
 * Eligibility, decided here because it is about entitlement, not about the closure:
 * a subscription is compensated when it is a DAY subscription of that provider, ACTIVE, confirmed by the provider, not
 * renewed, and entitled on at least one closure day. The credited days are the closure days inside its entitlement
 * (from the later of closure start and subscription start, to the earlier of closure end and expiry) minus days that an
 * already-resumed vacation gave back. A subscription that starts after the closure gets nothing; one that expires during
 * it gets the days up to its expiry. Not tied to the plan's absence-extension setting: this is the owner's decision.
 */
public interface ClosureCreditService {

    /** What applying a closure over {@code from}..{@code to} would do, without writing anything. */
    List<ClosureCredit> preview(UUID providerId, LocalDate from, LocalDate to);

    /**
     * Applies the closure to every eligible subscription of the provider: one adjustment each, expiry moved, all in the
     * caller's transaction (atomic). Idempotent: a subscription this closure already adjusted is skipped, so a retry
     * writes nothing new. Subscription rows are locked in id order. Must run in a transaction.
     */
    List<ClosureCredit> apply(UUID providerId, UUID closureId, LocalDate from, LocalDate to, UUID actorAccountId);

    /** What the closure has already applied (empty when it extended nothing). */
    List<ClosureCredit> appliedBy(UUID closureId);
}
