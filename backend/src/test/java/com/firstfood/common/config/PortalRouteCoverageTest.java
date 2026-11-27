package com.firstfood.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Audit F-14: every {@code /api/**} route sits behind exactly one door (staff, customer) or is deliberately shared
 * ({@link PortalPaths}). A new controller whose URL is in none of the lists fails here, instead of quietly shipping
 * without its portal gate the way {@code PUT /api/v1/me/person/address} once did. If this fails for your new route:
 * put the route under an existing prefix, or add a pattern to the right list in {@link PortalPaths}.
 */
class PortalRouteCoverageTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyApiRouteIsInExactlyOnePortalList() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            info.getPatternValues().stream().filter(p -> p.startsWith("/api/")).forEach(routes::add);
        }
        assertThat(routes).as("routes found (sanity: the mapping was read)").hasSizeGreaterThan(20);

        List<String> problems = new ArrayList<>();
        for (String route : routes) {
            String concrete = route.replaceAll("\\{[^}]*}", "x");
            int hits = (matches(PortalPaths.STAFF, concrete) ? 1 : 0)
                    + (matches(PortalPaths.CUSTOMER, concrete) ? 1 : 0)
                    + (matches(PortalPaths.SHARED, concrete) ? 1 : 0);
            if (hits != 1) {
                problems.add(route + " -> in " + hits + " lists");
            }
        }
        assertThat(problems).as("routes not in exactly one of STAFF / CUSTOMER / SHARED").isEmpty();
    }

    @Test
    void theCustomerListCoversSubPathsOfPersonMembershipsAndInvitations() {
        // The F-14 miss, pinned directly so it cannot regress even if the route scan above is ever loosened.
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me/person/address")).isTrue();
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me/person")).isTrue();
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me/memberships/x/leave")).isTrue();
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me/invitations/x/accept")).isTrue();
        // and the shared account routes are not accidentally gated
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me")).isFalse();
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me/email/otp/request")).isFalse();
        assertThat(matches(PortalPaths.CUSTOMER, "/api/v1/me/phone/otp/verify")).isFalse();
    }

    private static boolean matches(String[] patterns, String path) {
        PathPatternParser parser = new PathPatternParser();
        for (String pattern : patterns) {
            PathPattern compiled = parser.parse(pattern);
            if (compiled.matches(org.springframework.http.server.PathContainer.parsePath(path))) {
                return true;
            }
        }
        return false;
    }
}
