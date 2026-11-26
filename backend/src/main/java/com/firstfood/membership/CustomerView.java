package com.firstfood.membership;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a provider's customer list. {@code phone} and {@code address} are shown only to callers holding
 * MEMBERSHIP_VIEW on that provider, and only for ACTIVE (accepted, not yet left) memberships.
 */
public record CustomerView(
        UUID membershipId,
        UUID providerId,
        UUID personId,
        String fullName,
        String phone,
        /** The customer's saved address (for delivery), or null. Shown under the same MEMBERSHIP_VIEW as the phone. */
        AddressView address,
        MembershipStatus status,
        Instant joinedAt,
        Instant leftAt,
        UUID addedBy,
        UUID leftBy) {

    /**
     * Contact details (phone, address) are released only while the membership is ACTIVE, i.e. the customer accepted and
     * has not left. A former member stays in the history by name only (F-03): leaving takes the details away.
     */
    static CustomerView from(ProviderMembership m, Person person, String phone) {
        boolean active = m.isActive();
        return new CustomerView(m.getId(), m.getProviderId(), m.getPersonId(), person.getFullName(),
                active ? phone : null, active ? AddressView.of(person) : null, m.getStatus(), m.getJoinedAt(),
                m.getLeftAt(), m.getAddedBy(), m.getLeftBy());
    }
}
