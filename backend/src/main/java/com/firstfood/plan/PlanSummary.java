package com.firstfood.plan;

import java.math.BigDecimal;

/** How many ACTIVE plans a provider offers and their price range (all INR today; the currency is the plans' own). */
public record PlanSummary(int activePlans, BigDecimal minPrice, BigDecimal maxPrice, String currency) {

    public static PlanSummary none() {
        return new PlanSummary(0, null, null, "INR");
    }
}
