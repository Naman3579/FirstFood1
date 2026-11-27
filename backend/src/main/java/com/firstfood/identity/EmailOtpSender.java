package com.firstfood.identity;

/**
 * Delivers an OTP code to an email address (the proof-of-ownership step of adding or changing an email). Same
 * rule as {@link OtpSender}: business logic depends on this interface, never on a vendor SDK.
 */
public interface EmailOtpSender {

    void send(String email, String otpCode);
}
