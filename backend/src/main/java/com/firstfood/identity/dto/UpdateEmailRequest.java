package com.firstfood.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Email is treated as non-sensitive for Phase 2 purposes - it can be
 * changed with just a valid access token, unlike phone number, which
 * requires OTP re-verification (rules.md Rule 3.3) since it's the
 * authentication-critical identity.
 *
 * Same constraints as {@link EmailChangeRequest}: required, and at most 255 characters (the column is
 * varchar(255); a longer value used to reach the database and come back as a 500 - F-15).
 */
public record UpdateEmailRequest(@NotBlank @Email @Size(max = 255) String email) {
}
