package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Sign-up on a portal: the phone, the code that was texted to it, and the name to show. */
public record PortalSignUpRequest(
        @NotBlank @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code,
        @NotBlank @Size(max = 120) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String fullName) {

    public PortalSignUpRequest {
        phone = PhonePattern.addPlus(phone);
    }
}
