package com.firstfood.plan;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanLookupServiceImpl implements PlanLookupService {

    private final PlanRepository planRepository;
    private final SubscriptionPolicyRepository policyRepository;

    public PlanLookupServiceImpl(PlanRepository planRepository, SubscriptionPolicyRepository policyRepository) {
        this.planRepository = planRepository;
        this.policyRepository = policyRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PlanOffer> findOffer(UUID providerId, UUID planId) {
        return planRepository.findByIdAndProviderId(planId, providerId).map(plan -> {
            // A plan is always created together with policy version 1, so this cannot be absent.
            SubscriptionPolicy policy = policyRepository.findFirstByPlanIdOrderByVersionDesc(plan.getId())
                    .orElseThrow(() -> new IllegalStateException("Plan " + plan.getId() + " has no policy"));
            return new PlanOffer(plan.getId(), plan.getProviderId(), plan.getName(), plan.getConsumptionType(),
                    plan.getDurationDays(), plan.getMealQuantity(), plan.getPrice(), plan.getCurrency(),
                    plan.isActive(), policy.getVersion(), policy.terms());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanListing> findActiveListings(UUID providerId) {
        List<Plan> plans = planRepository.findByProviderIdAndStatusOrderByCreatedAtDesc(providerId, PlanStatus.ACTIVE);
        if (plans.isEmpty()) {
            return List.of();
        }
        Map<UUID, SubscriptionPolicy> current = policyRepository
                .findCurrentForPlans(plans.stream().map(Plan::getId).toList()).stream()
                .collect(Collectors.toMap(SubscriptionPolicy::getPlanId, Function.identity()));
        return plans.stream()
                .map(p -> new PlanListing(p.getId(), p.getProviderId(), p.getName(), p.getDescription(),
                        p.getConsumptionType(), p.getDurationDays(), p.getMealQuantity(), p.getPrice(),
                        p.getCurrency(), PlanView.PolicyView.from(current.get(p.getId()))))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PlanSummary> summarizeActive(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Plan>> byProvider = planRepository
                .findByProviderIdInAndStatus(providerIds, PlanStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(Plan::getProviderId));
        Map<UUID, PlanSummary> result = new HashMap<>();
        byProvider.forEach((providerId, plans) -> {
            BigDecimal min = plans.stream().map(Plan::getPrice).min(Comparator.naturalOrder()).orElse(null);
            BigDecimal max = plans.stream().map(Plan::getPrice).max(Comparator.naturalOrder()).orElse(null);
            result.put(providerId, new PlanSummary(plans.size(), min, max, plans.get(0).getCurrency()));
        });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> providersWithActivePlanPricedBetween(
            Collection<UUID> providerIds, BigDecimal min, BigDecimal max) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Set.of();
        }
        return planRepository.findByProviderIdInAndStatus(providerIds, PlanStatus.ACTIVE).stream()
                .filter(p -> min == null || p.getPrice().compareTo(min) >= 0)
                .filter(p -> max == null || p.getPrice().compareTo(max) <= 0)
                .map(Plan::getProviderId)
                .collect(Collectors.toSet());
    }
}
