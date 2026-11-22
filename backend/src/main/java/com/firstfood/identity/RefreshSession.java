package com.firstfood.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A revocable refresh session (architecture.md #12 "Rotate/revoke
 * refresh sessions where appropriate", "Store refresh-session state
 * securely"). The refresh token handed to the client is a random opaque
 * string; only its hash is stored here, the same way OTPs are hashed
 * rather than stored in plaintext.
 *
 * Revocation is rows-preserving (revokedAt timestamp) rather than a
 * delete, matching the project-wide "don't casually delete history"
 * principle (memory.md #3.7) even though sessions aren't business
 * history in the same sense subscriptions are.
 */
@Entity
@Table(name = "refresh_session")
public class RefreshSession {

    @Id
    private UUID id;

    @Column(name = "user_account_id", nullable = false)
    private UUID userAccountId;

    /** One login = one family; rotations keep it (F-11). The access token's {@code sid} claim is this value. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "refresh_token_hash", nullable = false, unique = true)
    private String refreshTokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 20)
    private RevokeReason revokedReason;

    protected RefreshSession() {
        // JPA
    }

    public RefreshSession(
            UUID userAccountId, UUID familyId, String refreshTokenHash, Instant issuedAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userAccountId = userAccountId;
        this.familyId = familyId;
        this.refreshTokenHash = refreshTokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Marks the row spent. A row that is already revoked keeps its original time and reason. */
    public void revoke(RevokeReason reason, Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserAccountId() {
        return userAccountId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public RevokeReason getRevokedReason() {
        return revokedReason;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
