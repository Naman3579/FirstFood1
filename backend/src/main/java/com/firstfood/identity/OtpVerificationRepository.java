package com.firstfood.identity;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, java.util.UUID> {

    /**
     * The most recent still-pending OTP for a phone number and purpose.
     * Used on verify - older pending OTPs for the same phone/purpose are
     * implicitly superseded (a new request always issues a fresh code).
     */
    Optional<OtpVerification> findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(
            String phone, OtpPurpose purpose, OtpStatus status);

    /**
     * Atomically spends a challenge: flips PENDING -> CONSUMED in one conditional UPDATE and returns the number of
     * rows changed (1 = this caller spent it, 0 = it was already consumed/expired/superseded). This is what makes an
     * OTP single-use under concurrency: the database serialises competing updates on the row, and the loser
     * re-evaluates the WHERE clause after the winner commits, so at most one caller ever gets 1.
     *
     * Deliberately NOT clearAutomatically: callers run inside a larger transaction and must keep their other managed
     * entities. The in-memory OtpVerification instance is stale afterwards and must not be saved again.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OtpVerification o
               set o.status = com.firstfood.identity.OtpStatus.CONSUMED
             where o.id = :id
               and o.status = com.firstfood.identity.OtpStatus.PENDING
               and o.expiresAt > :now
            """)
    int consumeIfPending(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
            @org.springframework.data.repository.query.Param("now") Instant now);

    /**
     * F-09: reserves one guess BEFORE the code is compared. One conditional UPDATE that adds 1 to the attempt counter
     * only while the challenge is still pending, unexpired and under {@code maxAttempts}; returns 1 if this caller
     * got a guess, 0 if the budget is spent (or the challenge just ended). Because the database serialises updates on
     * the row and the loser re-evaluates the WHERE clause after the winner commits, N parallel guesses can never
     * be granted more than {@code maxAttempts} comparisons in total - the old read-modify-write let parallel
     * requests overwrite each other's increments, so the cap of 5 was soft.
     *
     * The status is NOT flipped here: the last allowed guess may still be the right one. Use
     * {@link #expireIfExhausted} after a wrong guess. The in-memory entity is stale afterwards and must not be saved.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OtpVerification o
               set o.attemptCount = o.attemptCount + 1
             where o.id = :id
               and o.status = com.firstfood.identity.OtpStatus.PENDING
               and o.expiresAt > :now
               and o.attemptCount < :maxAttempts
            """)
    int reserveAttempt(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
            @org.springframework.data.repository.query.Param("now") Instant now,
            @org.springframework.data.repository.query.Param("maxAttempts") int maxAttempts);

    /** Gives back a guess reserved by a code that turned out to be CORRECT but was only checked, not spent. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OtpVerification o
               set o.attemptCount = o.attemptCount - 1
             where o.id = :id
               and o.status = com.firstfood.identity.OtpStatus.PENDING
               and o.attemptCount > 0
            """)
    int refundAttempt(@org.springframework.data.repository.query.Param("id") java.util.UUID id);

    /** After a wrong guess: kills the challenge once every allowed guess has been used. Atomic and idempotent. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OtpVerification o
               set o.status = com.firstfood.identity.OtpStatus.EXPIRED
             where o.id = :id
               and o.status = com.firstfood.identity.OtpStatus.PENDING
               and o.attemptCount >= :maxAttempts
            """)
    int expireIfExhausted(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
            @org.springframework.data.repository.query.Param("maxAttempts") int maxAttempts);

    /**
     * Phase 10 cleanup: deletes up to {@code limit} challenges that expired before {@code cutoff} (any status: an
     * expired challenge can never be used again). Batched because PostgreSQL has no DELETE ... LIMIT. Backed by
     * idx_otp_verification_expires_at. Returns the number deleted.
     */
    @Modifying
    @Query(value = """
            delete from otp_verification where id in (
                select id from otp_verification where expires_at < :cutoff order by expires_at limit :limit)
            """, nativeQuery = true)
    int deleteExpiredBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff,
            @org.springframework.data.repository.query.Param("limit") int limit);
}
