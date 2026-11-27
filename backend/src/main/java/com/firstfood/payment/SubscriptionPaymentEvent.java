package com.firstfood.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only audit of a payment: who submitted, verified or rejected it, and when (V17 forbids update/delete). */
@Entity
@Table(name = "subscription_payment_event")
public class SubscriptionPaymentEvent {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 20)
    private PaymentEventType eventType;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(updatable = false, length = 500)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected SubscriptionPaymentEvent() {
        // JPA
    }

    SubscriptionPaymentEvent(UUID paymentId, UUID providerId, PaymentEventType eventType, UUID actorAccountId,
            String detail, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId");
        this.detail = detail;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public PaymentEventType getEventType() {
        return eventType;
    }

    public UUID getActorAccountId() {
        return actorAccountId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
