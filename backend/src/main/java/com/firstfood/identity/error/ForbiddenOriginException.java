package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** A cookie-authenticated auth request came from an origin that is not on the allowlist (CSRF defence). */
public class ForbiddenOriginException extends DomainException {

    public ForbiddenOriginException() {
        super("FORBIDDEN_ORIGIN", "This request is not allowed from this origin.", HttpStatus.FORBIDDEN);
    }
}
