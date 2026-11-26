package com.firstfood.discovery;

import com.firstfood.subscription.MySubscriptionView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Customer-side discovery. Every method acts on public information or the caller's own account. */
public interface ProviderDiscoveryService {

    /** Providers matching the query, ranked; closed providers are never included. */
    List<ProviderCard> search(DiscoveryQuery query);

    /**
     * One provider's profile and active plans. {@code latitude}/{@code longitude} (both or neither) only add the
     * distance from the customer.
     *
     * @throws DiscoveryException PROVIDER_NOT_FOUND for an unknown or closed provider
     */
    ProviderDetail get(UUID providerId, Double latitude, Double longitude);

    /**
     * The caller subscribes to {@code planId}: joins the provider as a customer if they are not already (needs a
     * profile name and a provider that is accepting new customers), then buys the plan - all in one transaction.
     */
    MySubscriptionView subscribe(UUID accountId, UUID providerId, UUID planId, LocalDate startDate);
}
