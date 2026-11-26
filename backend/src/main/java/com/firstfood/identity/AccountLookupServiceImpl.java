package com.firstfood.identity;

import com.firstfood.identity.dto.PhonePattern;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountLookupServiceImpl implements AccountLookupService {

    private final UserAccountRepository repository;
    private final PhoneProperties phoneProperties;

    public AccountLookupServiceImpl(UserAccountRepository repository, PhoneProperties phoneProperties) {
        this.repository = repository;
        this.phoneProperties = phoneProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveAccountIdByPhone(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        // Accounts are stored in the one canonical form (F-13), so exactly one lookup can match.
        String canonical = PhonePattern.canonical(phone);
        if (canonical == null) {
            return Optional.empty();
        }
        return repository.findByPhoneAndPhoneVerifiedTrue(canonical)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .map(UserAccount::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findPhonesByIds(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(accountIds).stream()
                .filter(account -> account.getPhone() != null && account.isPhoneVerified())
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getPhone));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findVerifiedEmailsByIds(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(accountIds).stream()
                .filter(account -> account.getEmail() != null && account.isEmailVerified())
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getEmail));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findCustomerPhonesByIds(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(accountIds).stream()
                .filter(account -> account.getPhone() != null
                        && (account.isPhoneVerified() || !phoneProperties.verificationRequired()))
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getPhone));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPortal(UUID accountId, Portal portal) {
        return repository.findById(accountId).map(account -> account.hasPortal(portal)).orElse(false);
    }
}
