package com.firstfood.subscription;

/** Why a subscription's expiry moved without absence being involved (V25). Absence has its own {@code ExtensionEvent}. */
public enum AdjustmentKind {
    /** A vacation ended: the days the customer was away were given back. */
    VACATION_RESUME,
    /** The provider declared a closure and chose to extend: the closed days were given back. */
    PROVIDER_CLOSURE
}
