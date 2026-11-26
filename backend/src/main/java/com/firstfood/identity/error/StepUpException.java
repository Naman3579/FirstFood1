package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * A fresh-OTP (step-up) check for a sensitive action failed. Deliberately NOT 401: the caller is signed in, and a 401
 * makes clients drop or refresh the session, which is wrong for a mistyped code. Callers that run inside a transaction
 * must list this in {@code noRollbackFor}, or the failed-attempt count the OTP service just recorded is rolled back
 * with everything else and the attempt cap stops counting.
 */
public class StepUpException extends DomainException {

    private StepUpException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static StepUpException required() {
        return new StepUpException("STEP_UP_REQUIRED",
                "This action needs a fresh verification code. Request one, then enter it.", HttpStatus.FORBIDDEN);
    }

    public static StepUpException invalid() {
        return new StepUpException("STEP_UP_CODE_INVALID", "The verification code is incorrect.",
                HttpStatus.BAD_REQUEST);
    }

    public static StepUpException expired() {
        return new StepUpException("STEP_UP_CODE_EXPIRED",
                "That verification code has expired or was already used. Request a new one.", HttpStatus.BAD_REQUEST);
    }
<<<<<<< HEAD

    public static StepUpException noChannel() {
        return new StepUpException("STEP_UP_NO_CHANNEL",
                "Add and verify an email address on your account first, so we can send you a verification code.",
                HttpStatus.CONFLICT);
    }
=======
>>>>>>> origin/main
}
