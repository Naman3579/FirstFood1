package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** F-17: production must not run with the development database password. Plain JUnit. */
class LocalDefaultsGuardTest {

    @Test
    void productionWithTheDevPasswordIsRefused() {
        assertThatThrownBy(() -> LocalDefaultsGuard.check(true, "firstfood"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development database password");
    }

    @Test
    void productionWithARealPasswordAndNonProductionWithTheDevPasswordAreFine() {
        assertThatCode(() -> LocalDefaultsGuard.check(true, "8d3f1c0e-real-secret")).doesNotThrowAnyException();
        assertThatCode(() -> LocalDefaultsGuard.check(true, null)).doesNotThrowAnyException();
        assertThatCode(() -> LocalDefaultsGuard.check(false, "firstfood")).doesNotThrowAnyException();
    }
}
