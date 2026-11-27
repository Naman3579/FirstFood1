package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BotChallengePropertiesTest {

    @Test
    void enablingTheChallengeWithoutASecretFailsStartup() {
        assertThatThrownBy(() -> new BotChallengeProperties(true, "", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BotChallengeProperties(true, "   ", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BotChallengeProperties(true, null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDisabledChallengeNeedsNoSecret() {
        BotChallengeProperties properties = new BotChallengeProperties(false, null, null);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.verifyUrl()).startsWith("https://challenges.cloudflare.com/");
    }
}
