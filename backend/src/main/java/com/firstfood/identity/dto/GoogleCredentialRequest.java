package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The Google ID token (a JWT, typically ~1 KB) from Google Identity Services' {@code credential} field. */
public record GoogleCredentialRequest(@NotBlank @Size(max = 4096) String credential) {
}
