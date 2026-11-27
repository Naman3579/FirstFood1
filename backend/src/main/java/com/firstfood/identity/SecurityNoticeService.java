package com.firstfood.identity;

import java.util.UUID;

/**
 * Tells an account's owner that something sensitive happened (F-08: every owner-level change). Best effort by design:
 * a notice that cannot be delivered must never undo or block the change it reports.
 */
public interface SecurityNoticeService {

    /**
     * Sends {@code message} to the account's registered phone once the surrounding transaction commits (so nobody is
     * told about a change that was rolled back). With no transaction in progress it sends immediately. Never throws.
     */
    void notifyAfterCommit(UUID accountId, String message);
}
