package com.firstfood.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence only. The table is append-only: there is deliberately no query that changes a row. */
public interface SubscriptionPaymentEventRepository extends JpaRepository<SubscriptionPaymentEvent, UUID> {

    List<SubscriptionPaymentEvent> findByPaymentIdOrderByOccurredAtAsc(UUID paymentId);
}
