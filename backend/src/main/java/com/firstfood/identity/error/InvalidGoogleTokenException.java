package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** The Google credential is forged, expired, for another app, or its email is not verified by Google. */
public class InvalidGoogleTokenException extends DomainException {

    public InvalidGoogleTokenException() {
        super("INVALID_GOOGLE_TOKEN", "We could not verify your Google sign-in. Please try again.", HttpStatus.UNAUTHORIZED);
    }
}
