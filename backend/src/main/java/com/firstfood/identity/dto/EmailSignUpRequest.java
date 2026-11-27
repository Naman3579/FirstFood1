package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Sign-up on a portal by email: the address, the code that was emailed to it, and the name to show. */
public record EmailSignUpRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = EmailPattern.REGEX, message = "Enter a valid email address") String email,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code,
        @NotBlank @Size(max = 120) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String fullName) {

    public EmailSignUpRequest {
        email = EmailPattern.normalize(email);
    }
}
