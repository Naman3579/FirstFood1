package com.firstfood.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /**
     * Refresh tokens are hashed with a deterministic digest (see
     * {@link RefreshTokenHasher}), not BCrypt - unlike an OTP or
     * password, a refresh token is already a high-entropy random secret,
     * so a deterministic hash is safe here and (unlike BCrypt) supports a
     * direct equality lookup instead of needing a separate lookup key.
     */
    Optional<RefreshSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * Same lookup, but takes a {@code SELECT ... FOR UPDATE} row lock for
     * the rest of the transaction (FirstFood_V2_Phase2_Final_Review.md
     * #11). Without this, two concurrent refresh requests for the same
     * token can both read the session as "still active" before either
     * has committed its revocation, letting the same refresh token be
     * used twice. Use this - not the plain lookup above - anywhere a
     * refresh token is about to be validated-then-mutated (rotate,
     * revoke).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshSession r where r.refreshTokenHash = :hash")
    Optional<RefreshSession> findByRefreshTokenHashForUpdate(String hash);

    /** Scalar lookup (no entity is loaded, so a later load under the lock sees fresh state). */
    @Query("select r.userAccountId from RefreshSession r where r.refreshTokenHash = :hash")
    Optional<UUID> findAccountIdByRefreshTokenHash(@Param("hash") String hash);

    /**
     * F-11: serialises every session change for one account (rotate, logout, revoke-all) on a transaction-scoped
     * advisory lock. Without it a revoke-the-family or revoke-all statement can run in the gap while a rotation inserts
     * its replacement row, and the new row (invisible to that statement) survives. Held until commit/rollback.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) t", nativeQuery = true)
    Integer lockAccountSessions(@Param("key") String key);

    /** True while the family still has a usable row: the check that makes logout and revocation end access tokens. */
    @Query("select count(r) > 0 from RefreshSession r"
            + " where r.familyId = :family and r.revokedAt is null and r.expiresAt > :now")
    boolean isFamilyLive(@Param("family") UUID family, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshSession r set r.revokedAt = :now, r.revokedReason = :reason"
            + " where r.familyId = :family and r.revokedAt is null")
    int revokeFamily(@Param("family") UUID family, @Param("reason") RevokeReason reason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshSession r set r.revokedAt = :now, r.revokedReason = :reason"
            + " where r.userAccountId = :account and r.familyId <> :keep and r.revokedAt is null")
    int revokeAllExceptFamily(@Param("account") UUID account, @Param("keep") UUID keep,
            @Param("reason") RevokeReason reason, @Param("now") Instant now);

    /**
     * Phase 10 cleanup: deletes up to {@code limit} sessions that are dead for good - expired before {@code cutoff}, or
     * revoked before it. A live session (not revoked, not expired) never matches, and neither does a session that
     * only recently died (the retention period keeps it for investigation). Returns the number deleted.
     */
    @Modifying
    @Query(value = """
            delete from refresh_session where id in (
                select id from refresh_session
                 where expires_at < :cutoff or (revoked_at is not null and revoked_at < :cutoff)
                 order by issued_at limit :limit)
            """, nativeQuery = true)
    int deleteDeadBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff,
            @org.springframework.data.repository.query.Param("limit") int limit);
}
