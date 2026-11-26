package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Ask for a sign-in / sign-up code to be emailed. The address is trimmed and lower-cased on the way in. */
public record EmailOtpRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = EmailPattern.REGEX, message = "Enter a valid email address") String email) {

    public EmailOtpRequest {
        email = EmailPattern.normalize(email);
    }
}
