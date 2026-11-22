package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** The number must be proven with a code (verification is mandatory, or a proven number is being replaced). */
public class PhoneVerificationRequiredException extends DomainException {

    public PhoneVerificationRequiredException() {
        super("PHONE_VERIFICATION_REQUIRED", "This phone number must be verified with a code.", HttpStatus.CONFLICT);
    }
}
