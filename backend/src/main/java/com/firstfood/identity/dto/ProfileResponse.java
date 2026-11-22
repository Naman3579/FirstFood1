package com.firstfood.identity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProfileResponse(
        UUID accountId,
        String phone,
        /** false for no number and for a number that was added without a code. */
        boolean phoneVerified,
        String email,
        boolean emailVerified,
        String fullName,
        String status,
        List<String> portals,
        Instant createdAt) {
}
