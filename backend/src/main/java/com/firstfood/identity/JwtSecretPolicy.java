package com.firstfood.identity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a JWT signing secret must satisfy before the application will start (F-17). Pure functions, so the rules are
 * unit-tested without Spring. The messages never contain the secret itself.
 *
 * <p>Why each rule exists:
 * <ul>
 *   <li><b>At least 32 bytes</b> - HS256 is only as strong as its key; a short or guessable secret lets anyone who
 *       captures one token brute-force it offline and then mint tokens for any account.</li>
 *   <li><b>Not all-the-same / few distinct characters</b> - catches "aaaa...a" and "changeme..." padding that passes the
 *       length check but has almost no entropy. This is a sanity check, not an entropy proof: generate the value with
 *       {@code openssl rand -base64 48}.</li>
 *   <li><b>Not a value that has ever been committed to this repository</b> - the old local-profile secret is public
 *       forever (it is in version control), so it is refused in every profile. The test-profile secret is accepted
 *       only when the {@code test} profile is active.</li>
 *   <li><b>No {@code local}/{@code test} profile together with {@code production}</b> - those profiles relax security
 *       on purpose (plain-http cookies, one-call account creation).</li>
 * </ul>
 */
public final class JwtSecretPolicy {

    static final int MIN_BYTES = 32;
    static final int MIN_DISTINCT_CHARS = 12;

    /** Secrets that were committed to the repository at some point. The local one is refused everywhere. */
    static final String RETIRED_LOCAL_SECRET = "local-development-only-secret-do-not-use-in-production";

    /** The secret the test profile (src/test/resources/application-test.yml) uses. Accepted only under the test profile. */
    static final String TEST_PROFILE_SECRET = "test-only-secret-do-not-use-in-production";

    private JwtSecretPolicy() {
    }

    /** @return human-readable problems (empty = acceptable). Never includes the secret. */
    public static List<String> problems(String secret, Set<String> activeProfiles) {
        List<String> problems = new ArrayList<>();
        if (secret == null || secret.isBlank()) {
            problems.add("app.jwt.secret is empty");
            return problems;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
            problems.add("app.jwt.secret is shorter than " + MIN_BYTES + " bytes");
        }
        long distinct = secret.chars().distinct().count();
        if (distinct < MIN_DISTINCT_CHARS) {
            problems.add("app.jwt.secret has fewer than " + MIN_DISTINCT_CHARS + " distinct characters (looks like padding)");
        }
        if (secret.equals(RETIRED_LOCAL_SECRET)) {
            problems.add("app.jwt.secret is the old local-profile value that was committed to the repository");
        }
        if (secret.equals(TEST_PROFILE_SECRET) && !activeProfiles.contains("test")) {
            problems.add("app.jwt.secret is the test-profile value, which is only valid when the 'test' profile is active");
        }
        if (activeProfiles.contains("production")) {
            Set<String> relaxed = activeProfiles.stream()
                    .filter(p -> p.equals("local") || p.equals("test"))
                    .collect(Collectors.toSet());
            if (!relaxed.isEmpty()) {
                problems.add("the 'production' profile is active together with " + relaxed
                        + ", which relax security on purpose");
            }
        }
        return problems;
    }

    /** @throws IllegalStateException listing every problem, so one start-up attempt shows them all. */
    public static void require(String secret, Set<String> activeProfiles) {
        List<String> problems = problems(secret, activeProfiles);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: unsafe JWT configuration: "
                    + String.join("; ", problems)
                    + ". Set JWT_SECRET to at least 32 random bytes (for example: openssl rand -base64 48).");
        }
    }
}
