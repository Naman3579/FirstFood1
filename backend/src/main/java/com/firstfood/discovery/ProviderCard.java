package com.firstfood.discovery;

import com.firstfood.provider.FoodType;
import com.firstfood.provider.ProviderStatus;
import com.firstfood.provider.ProviderType;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * One provider as a customer sees it in search results and on its profile. Public facts only: no role, capacity or
 * closure data.
 *
 * @param distanceKm      null when the search had no location or the provider has no coordinates
 * @param available       taking new customers right now (ACTIVE and accepting)
 * @param activePlanCount how many plans can be subscribed to right now
 * @param minPrice        lowest / highest active plan price; null when there are no active plans
 */
public record ProviderCard(
        UUID id,
        String name,
        Set<ProviderType> providerTypes,
        FoodType foodType,
        String description,
        String addressLine,
        String locality,
        String city,
        String pincode,
        String contactPhone,
        ProviderStatus status,
        boolean acceptingNewCustomers,
        boolean available,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceKm,
        int activePlanCount,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String currency) {
}
