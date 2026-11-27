package com.firstfood.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local-only: prints the email OTP to the console (same narrow exception to the no-OTP-in-logs rule as the SMS one). */
@Component
@Profile("local")
public class DevConsoleEmailOtpSender implements EmailOtpSender {

    @Override
    public void send(String email, String otpCode) {
        System.out.println("[DEV EMAIL OTP - local/test only, never shown in production] email=" + email + " otp=" + otpCode);
    }
}
