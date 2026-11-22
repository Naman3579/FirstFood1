package com.firstfood.subscription;

import java.math.BigDecimal;

/** The price a subscription was sold at, exactly as frozen on its term snapshot. */
public record SubscriptionPrice(BigDecimal price, String currency) {
}
