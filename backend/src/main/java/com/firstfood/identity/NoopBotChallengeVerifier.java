package com.firstfood.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used while {@code app.bot-challenge.enabled} is false: accepts every request, including ones with no token. */
@Component
@ConditionalOnProperty(prefix = "app.bot-challenge", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopBotChallengeVerifier implements BotChallengeVerifier {

    @Override
    public void verify(String token, String clientIp) {
        // challenge disabled by configuration
    }
}
