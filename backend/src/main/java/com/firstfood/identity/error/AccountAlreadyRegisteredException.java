package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import com.firstfood.identity.Portal;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Sign-up on a portal where this number is already registered. Raised BEFORE the OTP is consumed. */
public class AccountAlreadyRegisteredException extends DomainException {

    public AccountAlreadyRegisteredException(Portal portal) {
        super("ACCOUNT_ALREADY_REGISTERED",
                "A " + portal.name().toLowerCase(Locale.ROOT) + " account already exists for this number. Sign in instead.",
                HttpStatus.CONFLICT);
    }

    private AccountAlreadyRegisteredException(String message) {
        super("ACCOUNT_ALREADY_REGISTERED", message, HttpStatus.CONFLICT);
    }

    /** Sign-up by email on a portal where this address is already registered. */
    public static AccountAlreadyRegisteredException forEmail(Portal portal) {
        return new AccountAlreadyRegisteredException("A " + portal.name().toLowerCase(Locale.ROOT)
                + " account already exists for this email. Sign in instead.");
    }
}
