package com.firstfood.subscription;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence only - the rules live in {@link PauseRules} and {@link SubscriptionPauseServiceImpl}. */
public interface SubscriptionPauseRepository extends JpaRepository<SubscriptionPause, UUID> {

    Optional<SubscriptionPause> findBySubscriptionIdAndStatus(UUID subscriptionId, PauseStatus status);

    List<SubscriptionPause> findBySubscriptionIdOrderByStartDateDescCreatedAtDesc(UUID subscriptionId);

    /** Vacations that were not called off, oldest first (open ones and resumed ones: the days the customer was away). */
    List<SubscriptionPause> findBySubscriptionIdInAndStatusNotOrderByStartDateAsc(
            Collection<UUID> subscriptionIds, PauseStatus status);

    /** Vacations in one state, oldest first (RESUMED ones are the ranges a provider closure must not pay for twice). */
    List<SubscriptionPause> findBySubscriptionIdAndStatusOrderByStartDateAsc(UUID subscriptionId, PauseStatus status);
}
