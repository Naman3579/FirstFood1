package com.firstfood.closure;

/** DECLARED closures are live; a CANCELLED one only exists if it had extended nothing. */
public enum ClosureStatus {
    DECLARED,
    CANCELLED
}
