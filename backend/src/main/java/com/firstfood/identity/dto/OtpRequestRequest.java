package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpRequestRequest(
        @NotBlank @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String phone) {

    public OtpRequestRequest {
        phone = PhonePattern.addPlus(phone);
    }
}
