package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Audit F-12: portal membership must never become an authorization signal. Only the two portal interceptors (which
 * choose which UI's API an account may reach) and the account/registration code itself may look at a portal. If this
 * fails because you added a new caller, ask whether the check really belongs to a role, ownership or membership
 * instead; if it truly is door-keeping, add the file here and explain why in the commit.
 */
class PortalUsageGuardTest {

    private static final Set<String> MAY_LOOK_AT_PORTALS = Set.of(
            "identity/Portal.java",
            "identity/UserAccount.java",
            "identity/AccountLookupService.java",
            "identity/AccountLookupServiceImpl.java",
            "identity/AuthenticationServiceImpl.java",
            "identity/security/StaffPortalInterceptor.java",
            "identity/security/CustomerPortalInterceptor.java");

    private static final List<String> MARKERS = List.of("hasPortal(", "Portal.STAFF", "Portal.CUSTOMER");

    @Test
    void onlyTheDoorsAndAccountRegistrationLookAtPortalMembership() throws IOException {
        Path root = Path.of("src/main/java/com/firstfood");
        assertThat(root).as("run from the backend module (mvn test does)").isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> !MAY_LOOK_AT_PORTALS.contains(rel))
                    .filter(rel -> MARKERS.stream().anyMatch(read(root.resolve(rel))::contains))
                    .toList();
        }

        assertThat(offenders).as("files deciding something from portal membership").isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
