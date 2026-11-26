package com.firstfood.provider;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * What customers may see of a provider: the public profile and where it is. No role, capacity or closure data.
 * A plain value handed to other modules (discovery) so they never touch {@link FoodProvider}.
 */
public record ProviderListing(
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
        BigDecimal latitude,
        BigDecimal longitude) {

    /** Taking on new customers right now (the intake switch is only ever on for an ACTIVE provider). */
    public boolean available() {
        return status == ProviderStatus.ACTIVE && acceptingNewCustomers;
    }

    static ProviderListing from(FoodProvider p) {
        return new ProviderListing(p.getId(), p.getName(), p.getProviderTypes(), p.getFoodType(), p.getDescription(),
                p.getAddressLine(), p.getLocality(), p.getCity(), p.getPincode(), p.getContactPhone(), p.getStatus(),
                p.isAcceptingNewCustomers(), p.getLatitude(), p.getLongitude());
    }
}
