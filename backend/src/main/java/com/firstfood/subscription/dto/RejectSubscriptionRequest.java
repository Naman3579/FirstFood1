package com.firstfood.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The reason is shown to the customer, so it is required. */
public record RejectSubscriptionRequest(@NotBlank @Size(max = 500) String reason) {
}
