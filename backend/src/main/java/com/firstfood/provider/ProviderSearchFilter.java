package com.firstfood.provider;

import java.util.Set;

/**
 * Candidate selection for the customer-side search. Every field is optional; closed providers are never returned.
 * The location box is only a coarse pre-filter ({@code radiusKm} around the point) - exact distance and ranking are
 * the caller's job.
 *
 * @param city            case-insensitive; with a location, also admits providers that have no coordinates but are
 *                        in this city (they simply have no distance)
 * @param availableOnly   ACTIVE and accepting new customers
 * @param query           case-insensitive text matched against name, locality and city
 * @param types           empty = any
 * @param foodTypes       empty = any
 * @param maxCandidates   hard cap on how many rows are read
 */
public record ProviderSearchFilter(
        Double latitude,
        Double longitude,
        Double radiusKm,
        String city,
        String query,
        Set<ProviderType> types,
        Set<FoodType> foodTypes,
        boolean availableOnly,
        int maxCandidates) {

    public boolean hasLocation() {
        return latitude != null && longitude != null && radiusKm != null;
    }
}
