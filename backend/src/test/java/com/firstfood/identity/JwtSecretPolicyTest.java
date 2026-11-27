package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** F-17: the start-up rules for the JWT signing secret. Plain JUnit, no Spring. */
class JwtSecretPolicyTest {

    private static final String GOOD = "kR7vQ2mX9pLw4ZtB8nYcF3sHjD6aGeU1oVxEi5MqNb0=";

    @Test
    void aLongRandomSecretIsAcceptedInEveryProfile() {
        assertThat(JwtSecretPolicy.problems(GOOD, Set.of("production"))).isEmpty();
        assertThat(JwtSecretPolicy.problems(GOOD, Set.of("local"))).isEmpty();
        assertThat(JwtSecretPolicy.problems(GOOD, Set.of())).isEmpty();
    }

    @Test
    void emptyAndShortSecretsAreRefused() {
        assertThat(JwtSecretPolicy.problems(null, Set.of())).isNotEmpty();
        assertThat(JwtSecretPolicy.problems("", Set.of())).isNotEmpty();
        assertThat(JwtSecretPolicy.problems("   ", Set.of())).isNotEmpty();
        assertThat(JwtSecretPolicy.problems("short-secret-31-characters-xyz", Set.of())).isNotEmpty();
        // exactly 32 bytes with enough variety is the minimum that passes
        assertThat(JwtSecretPolicy.problems("abcdefghijklmnopqrstuvwxyz012345", Set.of())).isEmpty();
        assertThat(JwtSecretPolicy.problems("abcdefghijklmnopqrstuvwxyz01234", Set.of())).isNotEmpty();
    }

    @Test
    void paddingWithFewDistinctCharactersIsRefused() {
        assertThat(JwtSecretPolicy.problems("a".repeat(64), Set.of())).isNotEmpty();
        assertThat(JwtSecretPolicy.problems("changeme".repeat(8), Set.of())).isNotEmpty();
    }

    @Test
    void theRetiredLocalSecretIsRefusedEverywhere() {
        for (Set<String> profiles : List.<Set<String>>of(Set.of("local"), Set.of("production"), Set.of("test"), Set.of())) {
            assertThat(JwtSecretPolicy.problems(JwtSecretPolicy.RETIRED_LOCAL_SECRET, profiles)).isNotEmpty();
        }
    }

    @Test
    void theTestSecretIsAcceptedOnlyUnderTheTestProfile() {
        assertThat(JwtSecretPolicy.problems(JwtSecretPolicy.TEST_PROFILE_SECRET, Set.of("test"))).isEmpty();
        assertThat(JwtSecretPolicy.problems(JwtSecretPolicy.TEST_PROFILE_SECRET, Set.of("production"))).isNotEmpty();
        assertThat(JwtSecretPolicy.problems(JwtSecretPolicy.TEST_PROFILE_SECRET, Set.of())).isNotEmpty();
    }

    @Test
    void productionTogetherWithLocalOrTestIsRefused() {
        assertThat(JwtSecretPolicy.problems(GOOD, Set.of("production", "local"))).hasSize(1);
        assertThat(JwtSecretPolicy.problems(GOOD, Set.of("production", "test"))).hasSize(1);
    }

    @Test
    void requireListsEveryProblemAndNeverEchoesTheSecret() {
        String weak = "a".repeat(10);
        assertThatThrownBy(() -> JwtSecretPolicy.require(weak, Set.of("production", "local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than 32")
                .hasMessageContaining("distinct")
                .hasMessageContaining("local")
                .hasMessageNotContaining(weak);
    }
}
