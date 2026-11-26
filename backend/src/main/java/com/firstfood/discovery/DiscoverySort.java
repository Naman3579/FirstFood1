package com.firstfood.discovery;

import java.util.Locale;
import java.util.Optional;

public enum DiscoverySort {
    /** Nearest first (the default when a location is given). */
    DISTANCE,
    /** Lowest starting plan price first. */
    PRICE,
    NAME,
    /** Most recently registered first (the default without a location). */
    NEWEST;

    public static Optional<DiscoverySort> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
