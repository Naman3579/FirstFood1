package com.firstfood.identity;

import com.firstfood.identity.dto.PhonePattern;
import com.firstfood.identity.error.AccountAlreadyRegisteredException;
import com.firstfood.identity.error.AccountNotRegisteredException;
import com.firstfood.identity.error.AccountSuspendedException;
import com.firstfood.identity.error.EmailAlreadyInUseException;
import com.firstfood.identity.error.OtpExpiredException;
import java.time.Instant;
import com.firstfood.identity.error.AuthenticatedAccountNotFoundException;
import com.firstfood.identity.error.InvalidOtpException;
import com.firstfood.identity.error.PhoneAlreadyInUseException;
import com.firstfood.identity.error.PhoneVerificationRequiredException;
import com.firstfood.identity.error.InvalidRefreshTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private final UserAccountRepository userAccountRepository;
    private final OtpService otpService;
    private final TokenService tokenService;
    private final RefreshSessionService refreshSessionService;
    private final LegacyLoginProperties legacyLogin;
    private final PhoneProperties phoneProperties;

    public AuthenticationServiceImpl(
            UserAccountRepository userAccountRepository,
            OtpService otpService,
            TokenService tokenService,
            RefreshSessionService refreshSessionService,
            LegacyLoginProperties legacyLogin,
            PhoneProperties phoneProperties) {
        this.userAccountRepository = userAccountRepository;
        this.otpService = otpService;
        this.tokenService = tokenService;
        this.refreshSessionService = refreshSessionService;
        this.legacyLogin = legacyLogin;
        this.phoneProperties = phoneProperties;
    }

    @Override
    public void requestLoginOtp(String phone) {
        otpService.requestOtp(PhonePattern.addPlus(phone), OtpPurpose.LOGIN);
    }

    @Override
    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    public AuthTokens verifyLoginOtp(String rawPhone, String code) {
        String phone = PhonePattern.addPlus(rawPhone);
        if (!legacyLogin.registerNewAccounts() && !userAccountRepository.existsByPhoneAndPhoneVerifiedTrue(phone)) {
            // F-12: the portal-less login no longer mints accounts. Prove the number first (a wrong or expired code
            // is rejected exactly as for a known number, so this is no registration oracle for someone without the
            // code), then refuse WITHOUT spending the code: the caller can sign up on the portal they want with it.
            otpService.checkOtp(phone, OtpPurpose.LOGIN, code);
            throw new AccountNotRegisteredException();
        }

        otpService.verifyOtp(phone, OtpPurpose.LOGIN, code);

        UserAccount account = userAccountRepository.findByPhoneAndPhoneVerifiedTrue(phone).orElseGet(() -> {
            // Only reached with registerNewAccounts=true (local/test): a brand-new number is registered on both portals.
            UserAccount created = new UserAccount(phone);
            Instant now = Instant.now();
            created.registerOn(Portal.CUSTOMER, now);
            created.registerOn(Portal.STAFF, now);
            UserAccount saved = userAccountRepository.save(created);
            userAccountRepository.releaseUnverifiedClaims(phone, saved.getId());
            return saved;
        });

        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        return issueTokenPair(account.getId());
    }

    @Override
    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    public AuthTokens signIn(Portal portal, String rawPhone, String code) {
        String phone = PhonePattern.addPlus(rawPhone);
        otpService.checkOtp(phone, OtpPurpose.LOGIN, code);

        UserAccount account = userAccountRepository.findByPhoneAndPhoneVerifiedTrue(phone)
                .filter(a -> a.hasPortal(portal))
                .orElseThrow(() -> new AccountNotRegisteredException(portal));
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        otpService.verifyOtp(phone, OtpPurpose.LOGIN, code);
        return issueTokenPair(account.getId());
    }

    @Override
    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    public AuthTokens signUp(Portal portal, String rawPhone, String code, String fullName) {
        String phone = PhonePattern.addPlus(rawPhone);
        otpService.checkOtp(phone, OtpPurpose.LOGIN, code);

        UserAccount existing = userAccountRepository.findByPhoneAndPhoneVerifiedTrue(phone).orElse(null);
        if (existing != null && existing.hasPortal(portal)) {
            throw new AccountAlreadyRegisteredException(portal);
        }
        if (existing != null && existing.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        otpService.verifyOtp(phone, OtpPurpose.LOGIN, code);

        UserAccount account = existing != null ? existing : new UserAccount(phone);
        account.registerOn(portal, Instant.now());
        String name = fullName == null ? "" : fullName.trim();
        if (!name.isEmpty() && (account.getFullName() == null || account.getFullName().isBlank())) {
            account.setFullName(name);
        }
        account = userAccountRepository.save(account);
        userAccountRepository.releaseUnverifiedClaims(phone, account.getId());
        return issueTokenPair(account.getId());
    }

    @Override
    public void requestEmailLoginOtp(String email) {
        otpService.requestEmailOtp(normalizeEmail(email), OtpPurpose.LOGIN);
    }

    @Override
    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    public AuthTokens signInWithEmail(Portal portal, String rawEmail, String code) {
        String email = normalizeEmail(rawEmail);
        otpService.checkOtp(email, OtpPurpose.LOGIN, code);

        UserAccount account = userAccountRepository.findByEmailAndEmailVerifiedTrue(email)
                .filter(a -> a.hasPortal(portal))
                .orElseThrow(() -> AccountNotRegisteredException.forEmail(portal));
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        otpService.verifyOtp(email, OtpPurpose.LOGIN, code);
        return issueTokenPair(account.getId());
    }

    @Override
    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    public AuthTokens signUpWithEmail(Portal portal, String rawEmail, String code, String fullName) {
        String email = normalizeEmail(rawEmail);
        otpService.checkOtp(email, OtpPurpose.LOGIN, code);

        UserAccount existing = userAccountRepository.findByEmailAndEmailVerifiedTrue(email).orElse(null);
        if (existing != null && existing.hasPortal(portal)) {
            throw AccountAlreadyRegisteredException.forEmail(portal);
        }
        if (existing != null && existing.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        otpService.verifyOtp(email, OtpPurpose.LOGIN, code);

        UserAccount account = existing != null ? existing : UserAccount.withVerifiedEmail(email);
        account.registerOn(portal, Instant.now());
        String name = fullName == null ? "" : fullName.trim();
        if (!name.isEmpty() && (account.getFullName() == null || account.getFullName().isBlank())) {
            account.setFullName(name);
        }
        account = userAccountRepository.save(account);
        return issueTokenPair(account.getId());
    }

    @Override
    public void requestEmailChangeOtp(java.util.UUID accountId, String email) {
        if (!userAccountRepository.existsById(accountId)) {
            throw new AuthenticatedAccountNotFoundException();
        }
        otpService.requestEmailOtp(normalizeEmail(email), OtpPurpose.EMAIL_CHANGE);
    }

    @Override
    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    public void verifyEmailChangeOtp(java.util.UUID accountId, String email, String code) {
        String normalized = normalizeEmail(email);
        otpService.verifyOtp(normalized, OtpPurpose.EMAIL_CHANGE, code);

        UserAccount account = userAccountRepository.findById(accountId)
                .orElseThrow(AuthenticatedAccountNotFoundException::new);
        // A verified email is a login identity, so it can belong to one account only (V28 unique index).
        if (userAccountRepository.findByEmailAndEmailVerifiedTrue(normalized)
                .filter(other -> !other.getId().equals(accountId)).isPresent()) {
            throw new EmailAlreadyInUseException();
        }
        account.setVerifiedEmail(normalized);
        userAccountRepository.save(account);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    // InvalidRefreshTokenException: a replayed token revokes its whole session family, and that must commit (F-11).
    @Transactional(noRollbackFor = {AccountSuspendedException.class, InvalidRefreshTokenException.class})
    public AuthTokens refresh(String rawRefreshToken) {
        RefreshSessionService.RotationResult rotation = refreshSessionService.rotate(rawRefreshToken);

        // Rotation above already revoked the old session and created a
        // new one - a suspended account's refresh attempt still "spends"
        // its old token (so it can't be retried/replayed), it just never
        // receives the new pair (rules.md/Phase2 review #12: suspended
        // accounts must not be able to refresh their way to a fresh
        // access token).
        UserAccount account = userAccountRepository.findById(rotation.accountId())
                .orElseThrow(InvalidRefreshTokenException::new);
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        String accessToken = tokenService.issueAccessToken(rotation.accountId(), rotation.familyId());
        return new AuthTokens(accessToken, rotation.rawRefreshToken());
    }

    @Override
    public void logout(String rawRefreshToken) {
        refreshSessionService.revoke(rawRefreshToken);
    }

    @Override
    public void requestPhoneChangeOtp(java.util.UUID accountId, String rawNewPhone) {
        String newPhone = PhonePattern.addPlus(rawNewPhone);
        if (userAccountRepository.existsByPhoneAndPhoneVerifiedTrue(newPhone)) {
            throw new PhoneAlreadyInUseException();
        }
        // Sent to the NEW number - proves the caller controls it before
        // the change is applied (rules.md Rule 3.3).
        otpService.requestOtp(newPhone, OtpPurpose.PHONE_CHANGE);
    }

    @Override
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public void verifyPhoneChangeOtp(
            java.util.UUID accountId, java.util.UUID currentSessionId, String rawNewPhone, String code) {
        String newPhone = PhonePattern.addPlus(rawNewPhone);
        otpService.verifyOtp(newPhone, OtpPurpose.PHONE_CHANGE, code);

        if (userAccountRepository.existsByPhoneAndPhoneVerifiedTrue(newPhone)) {
            // Re-check post-verification: another account could have
            // claimed this number in the window between request and verify.
            throw new PhoneAlreadyInUseException();
        }

        UserAccount account = userAccountRepository.findById(accountId)
                .orElseThrow(AuthenticatedAccountNotFoundException::new);
        account.setPhone(newPhone);
        userAccountRepository.save(account);
        userAccountRepository.releaseUnverifiedClaims(newPhone, accountId);

        // F-11: the number is the account's identity. Every other device or thief holding a session for the old
        // number is signed out (the session that made the change stays, so the user is not bounced).
        refreshSessionService.revokeOtherSessions(accountId, currentSessionId, RevokeReason.PHONE_CHANGE);
    }

    @Override
    @Transactional
    public void setUnverifiedPhone(java.util.UUID accountId, String rawPhone) {
        if (phoneProperties.verificationRequired()) {
            throw new PhoneVerificationRequiredException();
        }
        UserAccount account = userAccountRepository.findById(accountId)
                .orElseThrow(AuthenticatedAccountNotFoundException::new);
        String phone = PhonePattern.addPlus(rawPhone);
        if (account.getPhone() != null && account.isPhoneVerified()) {
            // A proven number is the account's identity; replacing it needs a code from the new number (Rule 3.3).
            throw new PhoneVerificationRequiredException();
        }
        if (userAccountRepository.existsByPhoneAndPhoneVerifiedTrue(phone)) {
            throw new PhoneAlreadyInUseException();
        }
        account.setUnverifiedPhone(phone);
        userAccountRepository.save(account);
    }

    private AuthTokens issueTokenPair(java.util.UUID accountId) {
        RefreshSessionService.IssuedSession session = refreshSessionService.issue(accountId);
        String accessToken = tokenService.issueAccessToken(accountId, session.familyId());
        return new AuthTokens(accessToken, session.rawRefreshToken());
    }
}
