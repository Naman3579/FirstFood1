package com.firstfood.provideraccess.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code phone} is who is being OFFERED the provider (nothing is looked up or revealed about it); {@code code} is the
 * fresh verification code sent to the owner's own phone. The actor comes from the JWT.
 */
public record OfferOwnershipRequest(
        @NotBlank @Size(max = 32) String phone,
        @Size(max = 12) String code) {
}
