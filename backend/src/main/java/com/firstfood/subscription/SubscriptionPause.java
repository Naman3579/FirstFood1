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
 * A customer's vacation (V25). Opened with a start date and no return date; closed when the customer resumes.
 * Dates follow one convention: the start day is the first day NOT served and the resume day is the first day served
 * again, so a pause 10 Nov -> 20 Nov withheld 10 days. Rows are never deleted; only an OPEN row changes (V25 trigger).
 */
@Entity
@Table(name = "subscription_pause")
public class SubscriptionPause {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PauseStatus status = PauseStatus.OPEN;

    @Column(name = "resumed_on")
    private LocalDate resumedOn;

    @Column(name = "paused_days")
    private Integer pausedDays;

    @Column(name = "credited_days")
    private Integer creditedDays;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionPause() {
        // JPA
    }

    SubscriptionPause(UUID subscriptionId, UUID providerId, LocalDate startDate, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    /** OPEN -> RESUMED on {@code resumeDate}; {@code creditedDays} of the withheld days are given back. */
    void resume(LocalDate resumeDate, int pausedDays, int creditedDays, UUID by, Instant at) {
        requireOpen();
        this.status = PauseStatus.RESUMED;
        this.resumedOn = Objects.requireNonNull(resumeDate, "resumeDate");
        this.pausedDays = pausedDays;
        this.creditedDays = creditedDays;
        this.closedBy = Objects.requireNonNull(by, "by");
        this.closedAt = Objects.requireNonNull(at, "at");
    }

    /** OPEN -> CANCELLED: the vacation never withheld a day (called off before it began, or the subscription was cancelled). */
    void cancel(UUID by, Instant at) {
        requireOpen();
        this.status = PauseStatus.CANCELLED;
        this.closedBy = Objects.requireNonNull(by, "by");
        this.closedAt = Objects.requireNonNull(at, "at");
    }

    private void requireOpen() {
        if (status != PauseStatus.OPEN) {
            throw new IllegalStateException("Vacation is " + status + " and can no longer change");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public PauseStatus getStatus() {
        return status;
    }

    public LocalDate getResumedOn() {
        return resumedOn;
    }

    public Integer getPausedDays() {
        return pausedDays;
    }

    public Integer getCreditedDays() {
        return creditedDays;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
