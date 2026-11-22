package com.firstfood.discovery;

import com.firstfood.plan.PlanListing;
import java.util.List;

/** A provider's public profile plus the plans a customer can subscribe to right now. */
public record ProviderDetail(ProviderCard provider, List<PlanListing> plans) {
}
