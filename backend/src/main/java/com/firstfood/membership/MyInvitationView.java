package com.firstfood.membership;

import java.time.Instant;
import java.util.UUID;

/** An open invitation as the invited customer sees it: who is asking, and until when. No contact details of anyone. */
public record MyInvitationView(
        UUID invitationId,
        UUID providerId,
        String providerName,
        Instant invitedAt,
        Instant expiresAt) {
}
