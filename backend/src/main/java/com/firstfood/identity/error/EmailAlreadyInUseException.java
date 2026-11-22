package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** The address is already the verified email of another account. */
public class EmailAlreadyInUseException extends DomainException {

    public EmailAlreadyInUseException() {
        super("EMAIL_ALREADY_IN_USE", "This email address is already associated with an account.", HttpStatus.CONFLICT);
    }
}
