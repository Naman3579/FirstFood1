package com.firstfood.provideraccess;

import java.time.Instant;
import java.util.UUID;

/**
 * What the person being offered a provider sees. The offering owner is shown only as the last digits of their phone:
 * enough to recognise them, not a way to collect owners' numbers.
 */
public record MyOwnershipOfferView(
        UUID id,
        UUID providerId,
        String providerName,
        String offeredByPhoneHint,
        Instant createdAt,
        Instant expiresAt) {
}
