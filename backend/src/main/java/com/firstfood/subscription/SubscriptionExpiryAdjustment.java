package com.firstfood.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One expiry change caused by a resumed vacation or an applied provider closure (V25): an immutable, append-only fact
 * that explains why {@code effective_expiry_date} is what it is. Absence-based extensions stay in {@code ExtensionEvent}.
 */
@Entity
@Table(name = "subscription_expiry_adjustment")
public class SubscriptionExpiryAdjustment {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AdjustmentKind kind;

    @Column(name = "pause_id", updatable = false)
    private UUID pauseId;

    @Column(name = "closure_id", updatable = false)
    private UUID closureId;

    @Column(name = "covered_from", nullable = false, updatable = false)
    private LocalDate coveredFrom;

    @Column(name = "covered_to", nullable = false, updatable = false)
    private LocalDate coveredTo;

    @Column(nullable = false, updatable = false)
    private int days;

    @Column(name = "previous_expiry_date", nullable = false, updatable = false)
    private LocalDate previousExpiryDate;

    @Column(name = "new_expiry_date", nullable = false, updatable = false)
    private LocalDate newExpiryDate;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionExpiryAdjustment() {
        // JPA
    }

    private SubscriptionExpiryAdjustment(UUID subscriptionId, UUID providerId, AdjustmentKind kind, UUID pauseId,
            UUID closureId, LocalDate coveredFrom, LocalDate coveredTo, int days, LocalDate previousExpiry,
            UUID createdBy) {
        if (days < 1) {
            throw new IllegalArgumentException("An adjustment must add at least one day");
        }
        this.id = UUID.randomUUID();
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.kind = kind;
        this.pauseId = pauseId;
        this.closureId = closureId;
        this.coveredFrom = Objects.requireNonNull(coveredFrom, "coveredFrom");
        this.coveredTo = Objects.requireNonNull(coveredTo, "coveredTo");
        this.days = days;
        this.previousExpiryDate = Objects.requireNonNull(previousExpiry, "previousExpiry");
        this.newExpiryDate = previousExpiry.plusDays(days);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    static SubscriptionExpiryAdjustment forVacation(UUID subscriptionId, UUID providerId, UUID pauseId,
            LocalDate coveredFrom, LocalDate coveredTo, int days, LocalDate previousExpiry, UUID createdBy) {
        return new SubscriptionExpiryAdjustment(subscriptionId, providerId, AdjustmentKind.VACATION_RESUME, pauseId,
                null, coveredFrom, coveredTo, days, previousExpiry, createdBy);
    }

    static SubscriptionExpiryAdjustment forClosure(UUID subscriptionId, UUID providerId, UUID closureId,
            LocalDate coveredFrom, LocalDate coveredTo, int days, LocalDate previousExpiry, UUID createdBy) {
        return new SubscriptionExpiryAdjustment(subscriptionId, providerId, AdjustmentKind.PROVIDER_CLOSURE, null,
                closureId, coveredFrom, coveredTo, days, previousExpiry, createdBy);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public AdjustmentKind getKind() {
        return kind;
    }

    public UUID getPauseId() {
        return pauseId;
    }

    public UUID getClosureId() {
        return closureId;
    }

    public LocalDate getCoveredFrom() {
        return coveredFrom;
    }

    public LocalDate getCoveredTo() {
        return coveredTo;
    }

    public int getDays() {
        return days;
    }

    public LocalDate getPreviousExpiryDate() {
        return previousExpiryDate;
    }

    public LocalDate getNewExpiryDate() {
        return newExpiryDate;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
