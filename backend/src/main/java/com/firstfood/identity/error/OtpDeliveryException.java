package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import com.firstfood.common.error.RetryAfterAware;
import org.springframework.http.HttpStatus;

/** The SMS provider failed or timed out. The OTP request is rolled back, so nothing half-created is left behind. */
public class OtpDeliveryException extends DomainException implements RetryAfterAware {

    public OtpDeliveryException() {
        super("OTP_DELIVERY_FAILED",
                "We could not send the verification code right now. Please try again shortly.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public long retryAfterSeconds() {
        return 30;
    }
}
