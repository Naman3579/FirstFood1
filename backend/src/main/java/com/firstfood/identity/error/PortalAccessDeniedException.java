package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import com.firstfood.identity.Portal;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** A signed-in account called an API that belongs to a portal it is not registered on. */
public class PortalAccessDeniedException extends DomainException {

    public PortalAccessDeniedException(Portal portal) {
        super("PORTAL_ACCESS_DENIED",
                "This account is not registered for the " + portal.name().toLowerCase(Locale.ROOT) + " portal.",
                HttpStatus.FORBIDDEN);
    }
}
