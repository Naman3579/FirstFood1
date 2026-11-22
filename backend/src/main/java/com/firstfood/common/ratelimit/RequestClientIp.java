package com.firstfood.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/** Request-scoped-by-attribute access to the resolved client address (resolved once per request). */
@Component
public class RequestClientIp {

    private static final String ATTRIBUTE = RequestClientIp.class.getName() + ".address";

    private final ClientIpResolver resolver;

    public RequestClientIp(ClientIpResolver resolver) {
        this.resolver = resolver;
    }

    /** The client address (see {@link ClientIpResolver}); {@code "unknown"} if the peer address is unusable. */
    public String of(HttpServletRequest request) {
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof String s) {
            return s;
        }
        List<String> forwarded = Collections.list(request.getHeaders("X-Forwarded-For"));
        String resolved = resolver.resolve(request.getRemoteAddr(), forwarded);
        request.setAttribute(ATTRIBUTE, resolved);
        return resolved;
    }

    /** The value rate limits are keyed on (IPv6 collapsed to its /64). */
    public String bucket(HttpServletRequest request) {
        return ClientIpResolver.bucket(of(request));
    }
}
