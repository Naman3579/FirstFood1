package com.firstfood.identity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test-only {@link EmailOtpSender}: remembers the latest code per (lower-cased) email so tests can verify it. */
@Component
@Profile("test")
public class TestCapturingEmailOtpSender implements EmailOtpSender {

    private final Map<String, String> lastOtpByEmail = new ConcurrentHashMap<>();

    @Override
    public void send(String email, String otpCode) {
        lastOtpByEmail.put(email, otpCode);
    }

    public String lastOtpFor(String email) {
        String code = lastOtpByEmail.get(email);
        if (code == null) {
            throw new IllegalStateException("No email OTP was sent to " + email + " in this test");
        }
        return code;
    }
}
