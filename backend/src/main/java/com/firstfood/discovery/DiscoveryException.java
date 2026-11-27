package com.firstfood.discovery;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class DiscoveryException extends DomainException {

    private DiscoveryException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static DiscoveryException invalidSearch(String message) {
        return new DiscoveryException("INVALID_SEARCH", message, HttpStatus.BAD_REQUEST);
    }

    /** Same answer for an unknown id and a CLOSED provider: customers never see closed providers. */
    public static DiscoveryException providerNotFound() {
        return new DiscoveryException("PROVIDER_NOT_FOUND", "Provider not found.", HttpStatus.NOT_FOUND);
    }
}
