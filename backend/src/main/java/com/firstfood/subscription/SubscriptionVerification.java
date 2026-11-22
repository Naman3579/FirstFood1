package com.firstfood.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 1:1 with a subscription: who asked for it and whether staff confirmed it (V20). Never deleted. */
@Entity
@Table(name = "subscription_verification")
public class SubscriptionVerification {

    @Id
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus status;

    @Column(name = "requested_by_account_id", nullable = false, updatable = false)
    private UUID requestedByAccountId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by_account_id")
    private UUID decidedByAccountId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    protected SubscriptionVerification() {
        // JPA
    }

    private SubscriptionVerification(UUID subscriptionId, UUID providerId, UUID requestedBy, Instant at) {
        this.subscriptionId = Objects.requireNonNull(subscriptionId);
        this.providerId = Objects.requireNonNull(providerId);
        this.requestedByAccountId = Objects.requireNonNull(requestedBy);
        this.requestedAt = Objects.requireNonNull(at);
    }

    /** A customer's own purchase: waits for staff. */
    static SubscriptionVerification pending(UUID subscriptionId, UUID providerId, UUID customerAccountId, Instant at) {
        SubscriptionVerification v = new SubscriptionVerification(subscriptionId, providerId, customerAccountId, at);
        v.status = VerificationStatus.PENDING;
        return v;
    }

    /** Sold by staff: the seller is the verifier. */
    static SubscriptionVerification verifiedBySeller(UUID subscriptionId, UUID providerId, UUID staffAccountId,
            Instant at) {
        SubscriptionVerification v = new SubscriptionVerification(subscriptionId, providerId, staffAccountId, at);
        v.status = VerificationStatus.VERIFIED;
        v.decidedByAccountId = staffAccountId;
        v.decidedAt = at;
        return v;
    }

    void verify(UUID staffAccountId, Instant at) {
        requirePending();
        this.status = VerificationStatus.VERIFIED;
        this.decidedByAccountId = Objects.requireNonNull(staffAccountId);
        this.decidedAt = Objects.requireNonNull(at);
    }

    void reject(UUID staffAccountId, String reason, Instant at) {
        requirePending();
        this.status = VerificationStatus.REJECTED;
        this.decidedByAccountId = Objects.requireNonNull(staffAccountId);
        this.decidedAt = Objects.requireNonNull(at);
        this.rejectionReason = Objects.requireNonNull(reason);
    }

    /**
     * PENDING -> EXPIRED (F-06): the request was not confirmed in time. A SYSTEM decision, so no staff account is
     * recorded and there is no reason text (V24 pins that shape). The caller cancels the subscription in the same
     * transaction.
     */
    void expire(Instant at) {
        requirePending();
        this.status = VerificationStatus.EXPIRED;
        this.decidedAt = Objects.requireNonNull(at);
    }

    boolean isPending() {
        return status == VerificationStatus.PENDING;
    }

    private void requirePending() {
        if (status != VerificationStatus.PENDING) {
            throw new IllegalStateException("Subscription verification is " + status + " and can no longer change");
        }
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public UUID getRequestedByAccountId() {
        return requestedByAccountId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getDecidedByAccountId() {
        return decidedByAccountId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }
}
