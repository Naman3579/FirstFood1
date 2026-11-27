package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Google sign-in is not configured on this server, or Google could not be reached to check the credential. */
public class GoogleSignInUnavailableException extends DomainException {

    public GoogleSignInUnavailableException() {
        super("GOOGLE_SIGNIN_UNAVAILABLE", "Google sign-in isn't available right now. Please use your email instead.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
