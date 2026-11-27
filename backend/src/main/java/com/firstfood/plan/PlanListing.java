package com.firstfood.plan;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a customer may see of an ACTIVE plan while browsing a provider: the commercial terms and the current policy.
 * No audit fields (who created or edited it). A plain value handed to the discovery module.
 */
public record PlanListing(
        UUID id,
        UUID providerId,
        String name,
        String description,
        ConsumptionType consumptionType,
        Integer durationDays,
        Integer mealQuantity,
        BigDecimal price,
        String currency,
        PlanView.PolicyView policy) {
}
