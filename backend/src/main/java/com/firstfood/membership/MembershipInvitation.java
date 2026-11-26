package com.firstfood.membership;

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
 * A provider's invitation to a phone number. Never deleted; it ends in exactly one of ACCEPTED, DECLINED, WITHDRAWN or
 * EXPIRED. Keyed by phone (not account) so that inviting an unregistered number looks identical to inviting a
 * registered one. Package-private to the membership module.
 */
@Entity
@Table(name = "membership_invitation")
class MembershipInvitation {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "invited_phone", nullable = false, updatable = false, length = 20)
    private String invitedPhone;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status = InvitationStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "membership_id")
    private UUID membershipId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipInvitation() {
        // JPA
    }

    MembershipInvitation(UUID providerId, String invitedPhone, UUID invitedBy, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.invitedPhone = Objects.requireNonNull(invitedPhone, "invitedPhone");
        this.invitedBy = Objects.requireNonNull(invitedBy, "invitedBy");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** PENDING and not yet past its expiry. */
    boolean isOpen(Instant now) {
        return status == InvitationStatus.PENDING && expiresAt.isAfter(now);
    }

    /** What callers should see: a PENDING row past its expiry reads as EXPIRED even before the system marks it. */
    InvitationStatus effectiveStatus(Instant now) {
        return status == InvitationStatus.PENDING && !expiresAt.isAfter(now) ? InvitationStatus.EXPIRED : status;
    }

    void accept(UUID byAccountId, UUID membershipId, Instant at) {
        requirePending();
        this.status = InvitationStatus.ACCEPTED;
        this.respondedBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.membershipId = Objects.requireNonNull(membershipId, "membershipId");
        this.respondedAt = Objects.requireNonNull(at, "at");
    }

    void decline(UUID byAccountId, Instant at) {
        requirePending();
        this.status = InvitationStatus.DECLINED;
        this.respondedBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.respondedAt = Objects.requireNonNull(at, "at");
    }

    void withdraw(UUID byAccountId, Instant at) {
        requirePending();
        this.status = InvitationStatus.WITHDRAWN;
        this.respondedBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.respondedAt = Objects.requireNonNull(at, "at");
    }

    void expire(Instant at) {
        requirePending();
        this.status = InvitationStatus.EXPIRED;
        this.respondedAt = Objects.requireNonNull(at, "at");
    }

    private void requirePending() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException("Invitation is not pending: " + status);
        }
    }

    UUID getId() {
        return id;
    }

    UUID getProviderId() {
        return providerId;
    }

    String getInvitedPhone() {
        return invitedPhone;
    }

    UUID getInvitedBy() {
        return invitedBy;
    }

    InvitationStatus getStatus() {
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

    UUID getMembershipId() {
        return membershipId;
    }
}
