package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** 400: the bot-challenge token was missing, invalid, expired or already used (F-05). The browser should get a fresh one. */
public class BotChallengeFailedException extends DomainException {

    public BotChallengeFailedException() {
        super("BOT_CHALLENGE_FAILED",
                "We could not confirm you are human. Please try again.",
                HttpStatus.BAD_REQUEST);
    }
}
