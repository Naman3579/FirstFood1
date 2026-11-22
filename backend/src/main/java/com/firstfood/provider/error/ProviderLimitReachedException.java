package com.firstfood.provider.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** An account already owns as many providers as it may (F-03). */
public class ProviderLimitReachedException extends DomainException {

    public ProviderLimitReachedException(String message) {
        super("PROVIDER_LIMIT_REACHED", message, HttpStatus.CONFLICT);
    }
}
