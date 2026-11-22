package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidPortalException extends DomainException {

    public InvalidPortalException() {
        super("PORTAL_INVALID", "Unknown portal. Use 'customer' or 'staff'.", HttpStatus.NOT_FOUND);
    }
}
