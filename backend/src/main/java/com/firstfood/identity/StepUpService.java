package com.firstfood.identity;

import java.util.UUID;

/**
 * Fresh proof that the person at the keyboard still controls the account's own phone, for actions too damaging to
 * allow on a 15-minute access token alone (F-08). The code always goes to the phone ON RECORD for the account - the
 * caller can never choose where it is sent - and is single-use.
 */
public interface StepUpService {

    /** Sends a new code to the account's registered phone. Counts against that phone's hourly OTP budget. */
    void requestCode(UUID accountId);

    /**
     * Checks and spends {@code code}. Call it only after every cheap precondition has passed, inside the same
     * transaction as the action: if the action then fails, the spend rolls back with it. Wrong codes count as failed
     * attempts. Throws {@link com.firstfood.identity.error.StepUpException}; transactional callers must list that in
     * {@code noRollbackFor}.
     */
    void consume(UUID accountId, String code);
}
