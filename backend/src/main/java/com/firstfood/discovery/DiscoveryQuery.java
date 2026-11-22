package com.firstfood.discovery;

import com.firstfood.provider.FoodType;
import com.firstfood.provider.ProviderType;
import java.math.BigDecimal;
import java.util.Set;

/**
 * A customer's search. Every field is optional.
 *
 * @param latitude      with longitude: where the customer is; providers are ranked by distance from here
 * @param radiusKm      how far to look (default 15, at most 500); only used with a location
 * @param city          case-insensitive; also admits providers without coordinates in that city
 * @param text          matched against name, locality and city
 * @param availableOnly only providers taking new customers now (ACTIVE and accepting); the default
 * @param minPrice      with maxPrice: keep providers that have an active plan priced within the range
 * @param limit         at most this many results (default 60, at most 200)
 */
public record DiscoveryQuery(
        Double latitude,
        Double longitude,
        Double radiusKm,
        String city,
        String text,
        Set<ProviderType> types,
        Set<FoodType> foodTypes,
        boolean availableOnly,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        DiscoverySort sort,
        Integer limit) {
}
