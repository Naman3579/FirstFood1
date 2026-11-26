package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Customer vacation with an unknown return date (V25). Every operation acts on the caller's OWN subscription: someone
 * else's is indistinguishable from a missing one (no IDOR). Backend is authoritative; no date arithmetic is left to the UI.
 */
public interface SubscriptionPauseService {

    /** Where the subscription stands on vacation. */
    VacationView status(UUID accountId, UUID subscriptionId);

    /**
     * Starts a vacation on {@code startDate} (null = today; a later date schedules it). The return date is unknown: the
     * vacation stays open until {@link #resume}. Refused for MEAL, ended, cancelled, renewed or unconfirmed
     * subscriptions, for a closed provider, or when a vacation is already open.
     */
    PauseView start(UUID accountId, UUID subscriptionId, LocalDate startDate);

    /**
     * Ends the open vacation today. A vacation that has not begun yet is simply called off. Otherwise the withheld days
     * (minus those a provider closure already compensated) are added to the expiry, recorded as an adjustment.
     * Idempotent: a repeated request returns the vacation as it was resolved and adds no day twice.
     */
    PauseView resume(UUID accountId, UUID subscriptionId);
}
