package com.firstfood.membership;

import java.time.Instant;
import java.util.UUID;

/**
 * The provider's view of an invitation. {@code phone} is the number the staff member typed, so it discloses nothing new.
 * Deliberately has NO field that depends on whether the number has an account (no person id, no name): the response to
 * inviting a registered number and an unregistered number must be indistinguishable.
 */
public record InvitationView(
        UUID invitationId,
        UUID providerId,
        String phone,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant respondedAt) {

    static InvitationView from(MembershipInvitation i, Instant now) {
        return new InvitationView(i.getId(), i.getProviderId(), i.getInvitedPhone(), i.effectiveStatus(now),
                i.getCreatedAt(), i.getExpiresAt(), i.getRespondedAt());
    }
}
