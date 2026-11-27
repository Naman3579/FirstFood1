package com.firstfood.payment;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence only - every business decision lives in {@link PaymentServiceImpl}. */
public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, UUID> {

    List<SubscriptionPayment> findBySubscriptionIdOrderBySubmittedAtDesc(UUID subscriptionId);

    Optional<SubscriptionPayment> findByCustomerAccountIdAndIdempotencyKey(UUID customerAccountId, String idempotencyKey);

    /** Row lock for a decision: two staff verifying/rejecting the same payment are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SubscriptionPayment p where p.id = :id and p.subscriptionId = :subscriptionId"
            + " and p.providerId = :providerId")
    Optional<SubscriptionPayment> findForDecision(@Param("id") UUID id, @Param("subscriptionId") UUID subscriptionId,
            @Param("providerId") UUID providerId);

    /** Sum of the subscription's payments in the given states (zero when there are none). */
    @Query("select coalesce(sum(p.amount), 0) from SubscriptionPayment p"
            + " where p.subscriptionId = :subscriptionId and p.verificationStatus in :statuses")
    BigDecimal sumAmount(@Param("subscriptionId") UUID subscriptionId,
            @Param("statuses") Collection<PaymentVerificationStatus> statuses);

    /**
     * A live (not rejected) UPI / bank payment already uses this reference anywhere in the system. The key is the
     * canonical form (see {@link PaymentReference}) and the method is deliberately not part of the match: one real
     * transfer cannot be claimed again under another method.
     */
    @Query("select count(p) > 0 from SubscriptionPayment p where p.paymentMethod in :methods"
            + " and p.referenceKey = :key"
            + " and p.verificationStatus <> com.firstfood.payment.PaymentVerificationStatus.REJECTED")
    boolean existsLiveDigitalReference(@Param("methods") Collection<PaymentMethod> digitalMethods,
            @Param("key") String referenceKey);

    /** Same, but only within one provider and method (cash / other receipt numbers). */
    @Query("select count(p) > 0 from SubscriptionPayment p where p.providerId = :providerId"
            + " and p.paymentMethod = :method and p.referenceKey = :key"
            + " and p.verificationStatus <> com.firstfood.payment.PaymentVerificationStatus.REJECTED")
    boolean existsLiveReferenceInProvider(@Param("providerId") UUID providerId,
            @Param("method") PaymentMethod method, @Param("key") String referenceKey);
}
