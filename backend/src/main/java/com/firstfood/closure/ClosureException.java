package com.firstfood.closure;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in provider closures. Use the factories. */
public class ClosureException extends DomainException {

    private ClosureException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static ClosureException notFound() {
        return new ClosureException("CLOSURE_NOT_FOUND", "Closure not found.", HttpStatus.NOT_FOUND);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its type). */
    public static ClosureException providerClosed() {
        return new ClosureException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    public static ClosureException datesInvalid(String message) {
        return new ClosureException("CLOSURE_DATES_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    public static ClosureException decisionRequired() {
        return new ClosureException("CLOSURE_DECISION_REQUIRED",
                "Say whether subscriptions should be extended for this closure. It is never decided for you.",
                HttpStatus.BAD_REQUEST);
    }

    public static ClosureException overlaps() {
        return new ClosureException("CLOSURE_OVERLAP",
                "A closure is already declared for some of those days. Cancel it first, or choose other dates.",
                HttpStatus.CONFLICT);
    }

    /** The same dates were declared before with the other extend decision: a retry must not flip the decision. */
    public static ClosureException decisionConflict() {
        return new ClosureException("CLOSURE_DECISION_CONFLICT",
                "These dates were already declared with a different decision about extending subscriptions.",
                HttpStatus.CONFLICT);
    }

    public static ClosureException hasExtensions() {
        return new ClosureException("CLOSURE_HAS_EXTENSIONS",
                "This closure has already extended subscriptions, so it cannot be cancelled: the extensions are part of "
                        + "each customer's history. (Reversing an applied closure is not supported yet.)",
                HttpStatus.CONFLICT);
    }

    public static ClosureException notDeclared() {
        return new ClosureException("CLOSURE_NOT_DECLARED", "This closure was already cancelled.", HttpStatus.CONFLICT);
    }

    public static ClosureException concurrentChange() {
        return new ClosureException("CLOSURE_CONCURRENT_CHANGE",
                "Something changed at the same moment. Reload and try again.", HttpStatus.CONFLICT);
    }
}
