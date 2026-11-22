package com.firstfood.closure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A provider's declared closure (V25). The dates and the owner's extend / don't-extend decision never change; the only
 * transition is DECLARED -> CANCELLED, and the database refuses it once the closure has extended any subscription
 * (history is corrected by a new decision, never rewritten). Rows are never deleted.
 */
@Entity
@Table(name = "provider_closure")
public class ProviderClosure {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Column(length = 200, updatable = false)
    private String reason;

    @Column(name = "extend_subscriptions", nullable = false, updatable = false)
    private boolean extendSubscriptions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ClosureStatus status = ClosureStatus.DECLARED;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected ProviderClosure() {
        // JPA
    }

    ProviderClosure(UUID providerId, LocalDate startDate, LocalDate endDate, String reason, boolean extendSubscriptions,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.endDate = Objects.requireNonNull(endDate, "endDate");
        this.reason = reason;
        this.extendSubscriptions = extendSubscriptions;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    void cancel(UUID by, Instant at) {
        if (status != ClosureStatus.DECLARED) {
            throw new IllegalStateException("Closure is " + status + " and can no longer change");
        }
        this.status = ClosureStatus.CANCELLED;
        this.cancelledBy = Objects.requireNonNull(by, "by");
        this.cancelledAt = Objects.requireNonNull(at, "at");
    }

    /** Closure days, both ends included: 15..17 is 3. */
    public int days() {
        return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getReason() {
        return reason;
    }

    public boolean isExtendSubscriptions() {
        return extendSubscriptions;
    }

    public ClosureStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
