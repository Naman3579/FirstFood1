package com.firstfood.closure;

import com.firstfood.closure.dto.DeclareClosureRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Provider closures (V25). Every operation authorizes the actor for THIS provider server-side before anything is read or
 * written; the frontend is never the security boundary. Viewing and previewing need {@code SUBSCRIPTION_VIEW}; declaring
 * and cancelling need {@code SUBSCRIPTION_MANAGE} (owner and manager roles; workers have neither).
 */
public interface ClosureService {

    /** Who would be extended, and to which date, if a closure over these days were declared with extension. */
    ClosurePreview preview(UUID actorAccountId, UUID providerId, LocalDate from, LocalDate to);

    /**
     * Records the closure and, when the owner chose to extend, applies every eligible subscription's extension in the
     * same transaction. Idempotent on (provider, dates): repeating the same declaration returns the recorded one.
     */
    DeclareClosureResult declare(UUID actorAccountId, UUID providerId, DeclareClosureRequest request);

    /** Closure history, newest first (counts only). */
    List<ClosureView> list(UUID actorAccountId, UUID providerId);

    ClosureView get(UUID actorAccountId, UUID providerId, UUID closureId);

    /** Cancels a closure that has extended nothing. One that already extended subscriptions is refused (409). */
    ClosureView cancel(UUID actorAccountId, UUID providerId, UUID closureId);
}
