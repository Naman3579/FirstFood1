package com.firstfood.provider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow lookup for other modules (membership, plan). Exists so those modules never
 * touch {@link FoodProvider} or its repository directly (rules.md Rule 27.3/27.4).
 * Performs NO authorization - callers must have called
 * {@link com.firstfood.provideraccess.ProviderAccessService#requirePermission} first.
 */
public interface ProviderLookupService {

    /**
     * Takes the provider row lock for the rest of the surrounding transaction and
     * returns its intake state. Must run inside an existing transaction.
     *
     * @throws com.firstfood.provideraccess.ProviderNotFoundException if the provider doesn't exist
     */
    ProviderIntake lockForIntake(UUID providerId);

    /**
     * Reads the provider's intake state WITHOUT locking it. For writers that only need "is it closed?" (attendance)
     * and must not serialize every customer of a provider behind one row lock. A provider closing at the same moment
     * can still let one in-flight write through; closing is final and attendance changes nothing about a closed
     * provider's commercial state, so that race is accepted.
     *
     * @throws com.firstfood.provideraccess.ProviderNotFoundException if the provider doesn't exist
     */
    ProviderIntake intakeOf(UUID providerId);

    /** Provider names for the given ids (unknown ids are simply absent). */
    Map<UUID, String> findNames(Collection<UUID> providerIds);

    /**
     * Candidate providers for the customer-side search, newest first, capped at
     * {@link ProviderSearchFilter#maxCandidates()}. CLOSED providers are never returned. The location is only a
     * coarse bounding box: the caller computes exact distances and trims to the radius.
     */
    List<ProviderListing> search(ProviderSearchFilter filter);

    /** The public profile of one provider; empty when it does not exist or is CLOSED. */
    Optional<ProviderListing> findListing(UUID providerId);
}
