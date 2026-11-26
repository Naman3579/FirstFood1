package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import com.firstfood.common.error.RetryAfterAware;
import org.springframework.http.HttpStatus;

/** 503: the bot-challenge service could not be reached, so the OTP request is refused (fails closed, F-05). */
public class BotChallengeUnavailableException extends DomainException implements RetryAfterAware {

    public BotChallengeUnavailableException() {
        super("BOT_CHALLENGE_UNAVAILABLE",
                "We could not verify your request right now. Please try again shortly.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public long retryAfterSeconds() {
        return 30;
    }
}
