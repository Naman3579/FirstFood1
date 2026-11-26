package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Add a phone number to the account without a code (only while phone verification is not mandatory). */
public record AddPhoneRequest(
        @NotBlank @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String phone) {

    public AddPhoneRequest {
        phone = PhonePattern.addPlus(phone);
    }
}
