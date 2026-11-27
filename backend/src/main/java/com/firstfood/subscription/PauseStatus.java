package com.firstfood.subscription;

/**
 * Lifecycle of a customer's vacation (V25). OPEN is the only state that can change: it ends RESUMED (the customer came
 * back, days were given back) or CANCELLED (it was called off before it began, or the subscription itself was cancelled).
 */
public enum PauseStatus {
    OPEN,
    RESUMED,
    CANCELLED
}
