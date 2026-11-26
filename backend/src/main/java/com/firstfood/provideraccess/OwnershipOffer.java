package com.firstfood.provideraccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An owner's offer to hand a provider to a phone number (F-08). Never deleted; it ends in exactly one of ACCEPTED,
 * DECLINED, WITHDRAWN or EXPIRED. Keyed by phone, not account, so offering to an unregistered number looks identical to
 * offering to a registered one. Package-private to the provideraccess module.
 */
@Entity
@Table(name = "ownership_offer")
class OwnershipOffer {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "offered_by", nullable = false, updatable = false)
    private UUID offeredBy;

    @Column(name = "target_phone", nullable = false, updatable = false, length = 20)
    private String targetPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnershipOfferStatus status = OwnershipOfferStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OwnershipOffer() {
        // JPA
    }

    OwnershipOffer(UUID providerId, UUID offeredBy, String targetPhone, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.offeredBy = Objects.requireNonNull(offeredBy, "offeredBy");
        this.targetPhone = Objects.requireNonNull(targetPhone, "targetPhone");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    boolean isOpen(Instant now) {
        return status == OwnershipOfferStatus.PENDING && expiresAt.isAfter(now);
    }

    /** A PENDING row past its expiry reads as EXPIRED even before the system records it. */
    OwnershipOfferStatus effectiveStatus(Instant now) {
        return status == OwnershipOfferStatus.PENDING && !expiresAt.isAfter(now)
                ? OwnershipOfferStatus.EXPIRED : status;
    }

    void accept(UUID byAccountId, Instant at) {
        close(OwnershipOfferStatus.ACCEPTED, Objects.requireNonNull(byAccountId, "byAccountId"), at);
    }

    void decline(UUID byAccountId, Instant at) {
        close(OwnershipOfferStatus.DECLINED, Objects.requireNonNull(byAccountId, "byAccountId"), at);
    }

    void withdraw(UUID byAccountId, Instant at) {
        close(OwnershipOfferStatus.WITHDRAWN, Objects.requireNonNull(byAccountId, "byAccountId"), at);
    }

    void expire(Instant at) {
        close(OwnershipOfferStatus.EXPIRED, null, at);
    }

    private void close(OwnershipOfferStatus to, UUID by, Instant at) {
        if (status != OwnershipOfferStatus.PENDING) {
            throw new IllegalStateException("Offer is not pending: " + status);
        }
        this.status = to;
        this.respondedBy = by;
        this.respondedAt = Objects.requireNonNull(at, "at");
    }

    UUID getId() {
        return id;
    }

    UUID getProviderId() {
        return providerId;
    }

    UUID getOfferedBy() {
        return offeredBy;
    }

    String getTargetPhone() {
        return targetPhone;
    }

    OwnershipOfferStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getRespondedAt() {
        return respondedAt;
    }
}
