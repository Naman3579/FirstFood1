package com.firstfood.provideraccess;

import java.time.Instant;
import java.util.UUID;

/** The owner's view of an offer they made. */
public record OwnershipOfferView(
        UUID id,
        UUID providerId,
        String targetPhone,
        OwnershipOfferStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant respondedAt) {

    static OwnershipOfferView from(OwnershipOffer o, Instant now) {
        return new OwnershipOfferView(o.getId(), o.getProviderId(), o.getTargetPhone(), o.effectiveStatus(now),
                o.getCreatedAt(), o.getExpiresAt(), o.getRespondedAt());
    }
}
