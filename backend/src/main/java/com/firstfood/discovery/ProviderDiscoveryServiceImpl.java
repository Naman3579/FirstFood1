package com.firstfood.discovery;

import com.firstfood.membership.MembershipService;
import com.firstfood.membership.MyMembershipView;
import com.firstfood.plan.PlanListing;
import com.firstfood.plan.PlanLookupService;
import com.firstfood.plan.PlanSummary;
import com.firstfood.provider.ProviderListing;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provider.ProviderSearchFilter;
import com.firstfood.subscription.MySubscriptionView;
import com.firstfood.subscription.SubscriptionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderDiscoveryServiceImpl implements ProviderDiscoveryService {

    static final double DEFAULT_RADIUS_KM = 15;
    static final double MAX_RADIUS_KM = 500;
    static final int DEFAULT_LIMIT = 60;
    static final int MAX_LIMIT = 200;
    /** Hard cap on rows read from the database before ranking. */
    private static final int MAX_CANDIDATES = 500;
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final ProviderLookupService providers;
    private final PlanLookupService plans;
    private final MembershipService memberships;
    private final SubscriptionService subscriptions;

    public ProviderDiscoveryServiceImpl(
            ProviderLookupService providers,
            PlanLookupService plans,
            MembershipService memberships,
            SubscriptionService subscriptions) {
        this.providers = providers;
        this.plans = plans;
        this.memberships = memberships;
        this.subscriptions = subscriptions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderCard> search(DiscoveryQuery q) {
        requireLocationPair(q.latitude(), q.longitude());
        if (q.minPrice() != null && q.maxPrice() != null && q.minPrice().compareTo(q.maxPrice()) > 0) {
            throw DiscoveryException.invalidSearch("minPrice cannot be greater than maxPrice.");
        }
        if (q.radiusKm() != null && q.radiusKm() <= 0) {
            throw DiscoveryException.invalidSearch("radiusKm must be positive.");
        }
        boolean located = q.latitude() != null;
        double radius = Math.min(q.radiusKm() != null ? q.radiusKm() : DEFAULT_RADIUS_KM, MAX_RADIUS_KM);
        int limit = Math.min(q.limit() != null && q.limit() > 0 ? q.limit() : DEFAULT_LIMIT, MAX_LIMIT);

        ProviderSearchFilter filter = new ProviderSearchFilter(
                q.latitude(), q.longitude(), located ? radius : null, q.city(), q.text(),
                q.types() == null ? Set.of() : q.types(), q.foodTypes() == null ? Set.of() : q.foodTypes(),
                q.availableOnly(), MAX_CANDIDATES);

        // Exact distance (the database only applied a bounding box). A provider without coordinates stays in the
        // results only when the city matched; it simply has no distance.
        Map<UUID, Double> distances = new java.util.HashMap<>();
        List<ProviderListing> found = new ArrayList<>();
        for (ProviderListing listing : providers.search(filter)) {
            if (located && listing.latitude() != null && listing.longitude() != null) {
                double km = distanceKm(q.latitude(), q.longitude(), listing.latitude().doubleValue(),
                        listing.longitude().doubleValue());
                if (km > radius) {
                    continue;
                }
                distances.put(listing.id(), km);
            }
            found.add(listing);
        }

        List<UUID> ids = found.stream().map(ProviderListing::id).toList();
        if (q.minPrice() != null || q.maxPrice() != null) {
            Set<UUID> inRange = plans.providersWithActivePlanPricedBetween(ids, q.minPrice(), q.maxPrice());
            found = found.stream().filter(p -> inRange.contains(p.id())).toList();
            ids = found.stream().map(ProviderListing::id).toList();
        }
        Map<UUID, PlanSummary> summaries = plans.summarizeActive(ids);

        DiscoverySort sort = q.sort() != null ? q.sort() : (located ? DiscoverySort.DISTANCE : DiscoverySort.NEWEST);
        List<ProviderCard> cards = found.stream()
                .map(p -> toCard(p, distances.get(p.id()), summaries.getOrDefault(p.id(), PlanSummary.none())))
                .sorted(comparator(sort))
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        return List.copyOf(cards);
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderDetail get(UUID providerId, Double latitude, Double longitude) {
        requireLocationPair(latitude, longitude);
        ProviderListing listing = providers.findListing(providerId).orElseThrow(DiscoveryException::providerNotFound);
        List<PlanListing> active = plans.findActiveListings(providerId);
        Double km = null;
        if (latitude != null && listing.latitude() != null && listing.longitude() != null) {
            km = distanceKm(latitude, longitude, listing.latitude().doubleValue(), listing.longitude().doubleValue());
        }
        BigDecimal min = active.stream().map(PlanListing::price).min(Comparator.naturalOrder()).orElse(null);
        BigDecimal max = active.stream().map(PlanListing::price).max(Comparator.naturalOrder()).orElse(null);
        PlanSummary summary = new PlanSummary(active.size(), min, max, "INR");
        return new ProviderDetail(toCard(listing, km, summary), active);
    }

    @Override
    @Transactional
    public MySubscriptionView subscribe(UUID accountId, UUID providerId, UUID planId, LocalDate startDate) {
        providers.findListing(providerId).orElseThrow(DiscoveryException::providerNotFound);
        MyMembershipView membership = memberships.joinProvider(accountId, providerId);
        // One transaction: if the sale is refused (overlap, capacity, ...) a membership created above rolls back too.
        return subscriptions.subscribeSelf(accountId, providerId, membership.membershipId(), planId, startDate);
    }

    // ---- helpers ----

    private static ProviderCard toCard(ProviderListing p, Double distanceKm, PlanSummary plan) {
        Double rounded = distanceKm == null ? null : Math.round(distanceKm * 10.0) / 10.0;
        return new ProviderCard(p.id(), p.name(), p.providerTypes(), p.foodType(), p.description(), p.addressLine(),
                p.locality(), p.city(), p.pincode(), p.contactPhone(), p.status(), p.acceptingNewCustomers(),
                p.available(), p.latitude(), p.longitude(), rounded, plan.activePlans(), plan.minPrice(),
                plan.maxPrice(), plan.currency());
    }

    private static Comparator<ProviderCard> comparator(DiscoverySort sort) {
        return switch (sort) {
            case DISTANCE -> Comparator.comparing(ProviderCard::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProviderCard::name, String.CASE_INSENSITIVE_ORDER);
            case PRICE -> Comparator.comparing(ProviderCard::minPrice, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProviderCard::name, String.CASE_INSENSITIVE_ORDER);
            case NAME -> Comparator.comparing(ProviderCard::name, String.CASE_INSENSITIVE_ORDER);
            // The lookup already returns newest first and sorted() is stable.
            case NEWEST -> (a, b) -> 0;
        };
    }

    private static void requireLocationPair(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw DiscoveryException.invalidSearch("Send both lat and lng, or neither.");
        }
        if (latitude != null && (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)) {
            throw DiscoveryException.invalidSearch("lat must be within -90..90 and lng within -180..180.");
        }
    }

    /** Great-circle (haversine) distance in kilometres. */
    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
