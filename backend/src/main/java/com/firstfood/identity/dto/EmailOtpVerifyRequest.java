package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Sign-in by email: the address and the code that was emailed to it. */
public record EmailOtpVerifyRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = EmailPattern.REGEX, message = "Enter a valid email address") String email,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code) {

    public EmailOtpVerifyRequest {
        email = EmailPattern.normalize(email);
    }
}
