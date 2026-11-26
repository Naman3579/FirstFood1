package com.firstfood.subscription;

import java.util.UUID;

/** Sum of adjustment days of one subscription (query projection). */
record AdjustmentTotal(UUID subscriptionId, long days) {
}
