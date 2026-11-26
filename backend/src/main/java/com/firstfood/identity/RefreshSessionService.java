package com.firstfood.identity;

import com.firstfood.identity.error.InvalidRefreshTokenException;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates, and revokes refresh sessions (architecture.md #12 "Rotate/revoke refresh sessions where
 * appropriate"). A login starts a FAMILY; every refresh spends the current token and issues the next one in the same
 * family, so there is at most one live token per family.
 *
 * <p>F-11 - theft handling. Presenting a token that was already rotated away means two parties hold it (the real user
 * and whoever copied it). Failing only the replay is not enough: whoever rotated first keeps a working session while
 * the real user is logged out. So a replay revokes the whole family, which also ends that family's access tokens
 * (they carry the family id as {@code sid}, see {@link TokenService}). Two things keep this from logging out honest
 * users: only ROTATED rows count (replaying a logged-out token does nothing new), and a replay within
 * {@code app.jwt.refresh-reuse-grace-seconds} of the rotation is just refused (two tabs refreshing at the same moment).
 *
 * <p>Every change for one account first takes the same advisory lock ({@link RefreshSessionRepository#lockAccountSessions}),
 * so "revoke the family" and "rotate" cannot interleave and leave a live replacement row behind.
 */
@Service
public class RefreshSessionService {

    private static final Logger log = LoggerFactory.getLogger(RefreshSessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshSessionRepository refreshSessionRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final JwtProperties jwtProperties;
    private final MeterRegistry meterRegistry;

    public RefreshSessionService(
            RefreshSessionRepository refreshSessionRepository,
            RefreshTokenHasher refreshTokenHasher,
            JwtProperties jwtProperties,
            MeterRegistry meterRegistry) {
        this.refreshSessionRepository = refreshSessionRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.jwtProperties = jwtProperties;
        this.meterRegistry = meterRegistry;
    }

    /** Starts a new session family (a login). */
    @Transactional
    public IssuedSession issue(UUID accountId) {
        return issueInFamily(accountId, UUID.randomUUID());
    }

    private IssuedSession issueInFamily(UUID accountId, UUID familyId) {
        String rawToken = generateOpaqueToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofDays(jwtProperties.refreshTokenTtlDays()));

        refreshSessionRepository.save(
                new RefreshSession(accountId, familyId, refreshTokenHasher.hash(rawToken), now, expiresAt));
        return new IssuedSession(rawToken, familyId);
    }

    /**
     * Validates the given refresh token, spends it, and issues the next one in the same family.
     *
     * <p>{@code noRollbackFor}: a replay must COMMIT its family revocation even though the request ends in a 401.
     * The per-account advisory lock is taken before the row is loaded, and the row is only ever loaded after it, so
     * the state we decide on is the committed state (FirstFood_V2_Phase2_Final_Review.md #11: no double use).
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotationResult rotate(String rawToken) {
        String hash = refreshTokenHasher.hash(rawToken);
        UUID accountId = refreshSessionRepository.findAccountIdByRefreshTokenHash(hash)
                .orElseThrow(InvalidRefreshTokenException::new);
        refreshSessionRepository.lockAccountSessions(accountId.toString());

        RefreshSession session = refreshSessionRepository.findByRefreshTokenHashForUpdate(hash)
                .orElseThrow(InvalidRefreshTokenException::new);
        Instant now = Instant.now();

        if (session.isActive(now)) {
            session.revoke(RevokeReason.ROTATED, now);
            refreshSessionRepository.save(session);
            IssuedSession next = issueInFamily(accountId, session.getFamilyId());
            return new RotationResult(accountId, next.familyId(), next.rawRefreshToken());
        }

        if (isTheftSignal(session, now)) {
            int revoked = refreshSessionRepository.revokeFamily(session.getFamilyId(), RevokeReason.REUSE, now);
            meterRegistry.counter("firstfood.auth.refresh.reuse").increment();
            // Ids only, never the token (rules.md Rule 17.4).
            log.warn("Refresh token reuse detected: session family {} of account {} revoked ({} live token(s) ended)",
                    session.getFamilyId(), accountId, revoked);
        }
        throw new InvalidRefreshTokenException();
    }

    private boolean isTheftSignal(RefreshSession session, Instant now) {
        return session.getRevokedReason() == RevokeReason.ROTATED
                && now.isBefore(session.getExpiresAt())
                && !now.isBefore(session.getRevokedAt().plusSeconds(jwtProperties.refreshReuseGraceSeconds()));
    }

    /** Logout: ends the whole session family, so its access token stops working at once too. Idempotent. */
    @Transactional
    public void revoke(String rawToken) {
        String hash = refreshTokenHasher.hash(rawToken);
        refreshSessionRepository.findAccountIdByRefreshTokenHash(hash).ifPresent(accountId -> {
            refreshSessionRepository.lockAccountSessions(accountId.toString());
            refreshSessionRepository.findByRefreshTokenHashForUpdate(hash).ifPresent(session ->
                    refreshSessionRepository.revokeFamily(session.getFamilyId(), RevokeReason.LOGOUT, Instant.now()));
        });
    }

    /**
     * Ends every session of the account except {@code keepFamilyId} (the caller's own, so changing your number does
     * not sign you out of the device you did it on). Returns how many live tokens were revoked.
     */
    @Transactional
    public int revokeOtherSessions(UUID accountId, UUID keepFamilyId, RevokeReason reason) {
        refreshSessionRepository.lockAccountSessions(accountId.toString());
        return refreshSessionRepository.revokeAllExceptFamily(accountId, keepFamilyId, reason, Instant.now());
    }

    /** Is the session behind an access token still alive? One indexed lookup per authenticated request. */
    @Transactional(readOnly = true)
    public boolean isSessionLive(UUID familyId) {
        return refreshSessionRepository.isFamilyLive(familyId, Instant.now());
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedSession(String rawRefreshToken, UUID familyId) {
    }

    public record RotationResult(UUID accountId, UUID familyId, String rawRefreshToken) {
    }
}
