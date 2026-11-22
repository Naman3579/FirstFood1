/**
 * closure module (V25): a provider declares that it is closed on one or more dates and chooses, explicitly, whether the
 * eligible DAY subscriptions are extended for those days.
 *
 * Owns the closure record and its authorization. Never touches a subscription itself: the compensation is done by
 * {@link com.firstfood.subscription.ClosureCreditService}, which records an append-only adjustment per subscription
 * (distinct from absence-based extension events). Only this module's application-service interfaces should be called from
 * other modules.
 */
package com.firstfood.closure;
