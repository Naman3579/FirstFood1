package com.firstfood.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow, read-only identity lookup for other modules (e.g. provideraccess
 * resolving a role-assignment target). Other modules must not reach into
 * {@link UserAccountRepository} directly.
 */
public interface AccountLookupService {

    /**
     * Normalizes the phone (trims, drops spaces and hyphens) and returns the id
     * of the ACTIVE account whose VERIFIED number it is. Empty for malformed input,
     * unknown numbers and SUSPENDED accounts - callers must not distinguish them.
     * The phone is only a lookup key, never an authorization credential.
     */
    Optional<UUID> findActiveAccountIdByPhone(String phone);

    /** VERIFIED phone numbers for the given account ids (unknown ids, email-only accounts and unproven numbers are absent). Safe to base access decisions on. */
    Map<UUID, String> findPhonesByIds(Collection<UUID> accountIds);

    /**
     * Phone numbers to show a CUSTOMER-facing screen or to match a provider's invitation against: verified numbers
     * always, and unverified ones only while {@code app.phone.verification-required} is off. Never use this to decide
     * access (roles, ownership) - use {@link #findPhonesByIds}.
     */
    Map<UUID, String> findCustomerPhonesByIds(Collection<UUID> accountIds);

    /**
     * True when the account exists and is registered on the portal (CUSTOMER = "/", STAFF = "/staff"). For choosing
     * which UI's API a caller may reach only - NOT an authorization signal (F-12, see {@link Portal}).
     */
    boolean hasPortal(UUID accountId, Portal portal);
}
