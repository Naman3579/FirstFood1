package com.firstfood.discovery.web;

import com.firstfood.discovery.DiscoveryException;
import com.firstfood.discovery.DiscoveryQuery;
import com.firstfood.discovery.DiscoverySort;
import com.firstfood.discovery.ProviderCard;
import com.firstfood.discovery.ProviderDetail;
import com.firstfood.discovery.ProviderDiscoveryService;
import com.firstfood.discovery.dto.SubscribeRequest;
import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.provider.FoodType;
import com.firstfood.provider.ProviderType;
import com.firstfood.subscription.MySubscriptionView;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer portal's discovery API. Thin HTTP layer: parses the query and delegates. Deliberately NOT under
 * /providers (that tree is the staff portal's and needs a staff registration); the customer-portal interceptor
 * guards this one.
 */
@RestController
@RequestMapping("/api/v1/discover")
public class DiscoveryController {

    private final ProviderDiscoveryService discovery;

    public DiscoveryController(ProviderDiscoveryService discovery) {
        this.discovery = discovery;
    }

    /**
     * Nearby providers. With {@code lat}+{@code lng}: those within {@code radiusKm} (default 15), nearest first.
     * Without: everything matching, newest first. Repeat or comma-separate {@code type} and {@code foodType}.
     */
    @GetMapping("/providers")
    public List<ProviderCard> search(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, name = "q") String text,
            @RequestParam(required = false, name = "type") Set<ProviderType> types,
            @RequestParam(required = false, name = "foodType") Set<FoodType> foodTypes,
            @RequestParam(defaultValue = "true") boolean availableOnly,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit) {
        DiscoverySort parsedSort = null;
        if (sort != null && !sort.isBlank()) {
            parsedSort = DiscoverySort.parse(sort).orElseThrow(
                    () -> DiscoveryException.invalidSearch("sort must be one of distance, price, name, newest."));
        }
        return discovery.search(new DiscoveryQuery(lat, lng, radiusKm, city, text, types, foodTypes, availableOnly,
                minPrice, maxPrice, parsedSort, limit));
    }

    @GetMapping("/providers/{providerId}")
    public ProviderDetail get(
            @PathVariable UUID providerId,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return discovery.get(providerId, lat, lng);
    }

    /** Joins the provider (if not yet a member) and buys the plan, atomically. */
    @PostMapping("/providers/{providerId}/subscribe")
    public ResponseEntity<MySubscriptionView> subscribe(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody SubscribeRequest request) {
        MySubscriptionView created =
                discovery.subscribe(principal.accountId(), providerId, request.planId(), request.startDate());
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/" + created.id())).body(created);
    }
}
