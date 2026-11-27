package com.firstfood.closure;

/** {@code created} is false when the same declaration was already recorded (a retry): nothing was applied again. */
public record DeclareClosureResult(boolean created, ClosureView closure) {
}
