package com.firstfood.provider;

import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitRule;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.common.ratelimit.RateLimitedException;
import com.firstfood.identity.SecurityNoticeService;
import com.firstfood.identity.StepUpService;
import com.firstfood.identity.error.StepUpException;
import com.firstfood.provider.dto.CreateProviderRequest;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.provider.dto.UpdateProviderRequest;
import com.firstfood.provider.error.InvalidProviderRequestException;
import com.firstfood.provider.error.ProviderClosedException;
import com.firstfood.provider.error.ProviderLimitReachedException;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderNotFoundException;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.provideraccess.ProviderRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderServiceImpl implements ProviderService {

    private final FoodProviderRepository providerRepository;
    private final ProviderAccessService accessService;

    private final RateLimitService rateLimitService;
    private final StepUpService stepUp;
    private final SecurityNoticeService notices;
    private final int maxOwnedPerAccount;
    private final int maxCreatedPerAccountPerDay;

    public ProviderServiceImpl(
            FoodProviderRepository providerRepository,
            ProviderAccessService accessService,
            RateLimitService rateLimitService,
            StepUpService stepUp,
            SecurityNoticeService notices,
            @Value("${app.provider.max-owned-per-account:10}") int maxOwnedPerAccount,
            @Value("${app.provider.max-created-per-account-per-day:10}") int maxCreatedPerAccountPerDay) {
        this.providerRepository = providerRepository;
        this.accessService = accessService;
        this.rateLimitService = rateLimitService;
        this.stepUp = stepUp;
        this.notices = notices;
        this.maxOwnedPerAccount = Math.max(1, maxOwnedPerAccount);
        this.maxCreatedPerAccountPerDay = Math.max(1, maxCreatedPerAccountPerDay);
    }

    @Override
    @Transactional
    public ProviderResponse create(UUID accountId, CreateProviderRequest request) {
        enforceCreationLimits(accountId);
        FoodProvider provider = new FoodProvider(
                request.name().trim(),
                request.providerTypes(),
                request.addressLine().trim(),
                request.locality().trim(),
                request.city().trim(),
                request.maxActiveSubscriptions());
        provider.setDescription(blankToNull(request.description()));
        provider.setPincode(request.pincode());
        provider.setContactPhone(request.contactPhone());
        if (request.foodType() != null) {
            provider.setFoodType(request.foodType());
        }
        provider.setLocation(request.latitude(), request.longitude());

        providerRepository.saveAndFlush(provider);
        // Same transaction: the provider and its mandatory OWNER row commit
        // together or not at all (freeze #4).
        accessService.assignOwner(provider.getId(), accountId);

        return toResponse(provider, ProviderRole.OWNER);
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderResponse get(UUID accountId, UUID providerId) {
        ProviderRole role = accessService.requirePermission(accountId, providerId, ProviderPermission.PROVIDER_VIEW);
        FoodProvider provider = providerRepository.findById(providerId).orElseThrow(ProviderNotFoundException::new);
        return toResponse(provider, role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderResponse> list(UUID accountId) {
        Map<UUID, ProviderRole> roles = accessService.rolesFor(accountId);
        if (roles.isEmpty()) {
            return List.of();
        }
        roles.values().removeIf(role -> !role.grants(ProviderPermission.PROVIDER_VIEW));
        if (roles.isEmpty()) {
            return List.of();
        }
        return providerRepository.findByIdInOrderByCreatedAtDesc(roles.keySet()).stream()
                .map(provider -> toResponse(provider, roles.get(provider.getId())))
                .toList();
    }

    /** noRollbackFor: a wrong step-up code must keep the failed-attempt count the OTP service just recorded. */
    @Override
    @Transactional(noRollbackFor = StepUpException.class)
    public ProviderResponse update(UUID accountId, UUID providerId, UpdateProviderRequest request) {
        ProviderRole role = accessService.requirePermission(accountId, providerId, ProviderPermission.PROVIDER_EDIT);
        FoodProvider provider = providerRepository.findByIdForUpdate(providerId)
                .orElseThrow(ProviderNotFoundException::new);

        if (provider.getStatus() == ProviderStatus.CLOSED) {
            throw new ProviderClosedException();
        }
        validateRequestConsistency(request);

        boolean closing = request.status() == ProviderStatus.CLOSED && request.status() != provider.getStatus();
        if (closing) {
            accessService.requirePermission(accountId, providerId, ProviderPermission.PROVIDER_CLOSE);
            // Closing is one-way, so a stolen token is not enough: a fresh code sent to the owner's own phone (F-08).
            // Spent in this transaction, so if anything below fails the code is not lost. For a reversible stop, an
            // owner or manager can use TEMPORARILY_UNAVAILABLE instead, which needs no code.
            stepUp.consume(accountId, request.stepUpCode());
        }

        applyProfile(provider, request);
        applyCapacity(provider, request);
        applyStatus(provider, request, accountId);

        providerRepository.saveAndFlush(provider);
        if (closing) {
            notices.notifyAfterCommit(accountId, "FirstFood: " + provider.getName() + " was closed permanently."
                    + " Not you? Contact support at once.");
        }
        return toResponse(provider, role);
    }

    private void validateRequestConsistency(UpdateProviderRequest request) {
        if (Boolean.TRUE.equals(request.unlimitedCapacity()) && request.maxActiveSubscriptions() != null) {
            throw new InvalidProviderRequestException(
                    "Send either maxActiveSubscriptions or unlimitedCapacity=true, not both.");
        }
        if (Boolean.TRUE.equals(request.clearLocation()) && (request.latitude() != null || request.longitude() != null)) {
            throw new InvalidProviderRequestException("Send either a location or clearLocation=true, not both.");
        }
        boolean closing = request.status() == ProviderStatus.CLOSED;
        if (closing && Boolean.TRUE.equals(request.acceptingNewCustomers())) {
            // Checked here, before the step-up code is asked for, so a malformed request is a 400 and never a 403.
            throw new InvalidProviderRequestException(
                    "A provider that is " + ProviderStatus.CLOSED + " cannot accept new customers.");
        }
        if (request.closureReason() != null && !closing) {
            throw new InvalidProviderRequestException("closureReason can only be sent when closing a provider.");
        }
    }

    private void applyProfile(FoodProvider provider, UpdateProviderRequest request) {
        if (request.name() != null) {
            provider.setName(request.name().trim());
        }
        if (request.providerTypes() != null) {
            provider.setProviderTypes(request.providerTypes());
        }
        if (request.description() != null) {
            provider.setDescription(blankToNull(request.description()));
        }
        if (request.addressLine() != null) {
            provider.setAddressLine(request.addressLine().trim());
        }
        if (request.locality() != null) {
            provider.setLocality(request.locality().trim());
        }
        if (request.city() != null) {
            provider.setCity(request.city().trim());
        }
        if (request.pincode() != null) {
            provider.setPincode(request.pincode());
        }
        if (request.contactPhone() != null) {
            provider.setContactPhone(request.contactPhone());
        }
        if (request.foodType() != null) {
            provider.setFoodType(request.foodType());
        }
        if (Boolean.TRUE.equals(request.clearLocation())) {
            provider.setLocation(null, null);
        } else if (request.latitude() != null || request.longitude() != null) {
            provider.setLocation(request.latitude(), request.longitude());
        }
    }

    /** Stores/validates capacity only - subscription-side enforcement is a later phase. */
    private void applyCapacity(FoodProvider provider, UpdateProviderRequest request) {
        if (Boolean.TRUE.equals(request.unlimitedCapacity())) {
            provider.setMaxActiveSubscriptions(null);
        } else if (request.maxActiveSubscriptions() != null) {
            provider.setMaxActiveSubscriptions(request.maxActiveSubscriptions());
        }
    }

    private void applyStatus(FoodProvider provider, UpdateProviderRequest request, UUID actorAccountId) {
        ProviderStatus target = request.status();
        boolean changing = target != null && target != provider.getStatus();

        if (changing && target == ProviderStatus.CLOSED) {
            provider.close(actorAccountId, blankToNull(request.closureReason()),
                    request.acceptingNewCustomers(), Instant.now());
        } else if (changing) {
            provider.transitionTo(target, request.acceptingNewCustomers());
        } else if (request.acceptingNewCustomers() != null) {
            provider.setIntake(request.acceptingNewCustomers());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProviderResponse toResponse(FoodProvider p, ProviderRole myRole) {
        return new ProviderResponse(
                p.getId(), p.getName(), p.getProviderTypes(), p.getFoodType(), p.getDescription(), p.getAddressLine(),
                p.getLocality(), p.getCity(), p.getPincode(), p.getContactPhone(), p.getLatitude(),
                p.getLongitude(), p.getStatus(),
                p.isAcceptingNewCustomers(), p.getMaxActiveSubscriptions(), myRole, p.getCreatedAt(),
                p.getUpdatedAt(), p.getClosedAt(), p.getClosedByAccountId(), p.getClosureReason(),
                myRole.permissions());
    }

    /**
     * F-03: anyone can sign up on the staff portal, and a new provider starts active and accepting customers, so cheap
     * throwaway accounts could otherwise mass-produce providers. Two caps per account: how many it may own at once, and
     * how many it may create per day. (Vetting / approval before a provider becomes visible is a product decision and is
     * not implemented here.)
     */
    private void enforceCreationLimits(UUID accountId) {
        long owned = accessService.rolesFor(accountId).values().stream().filter(r -> r == ProviderRole.OWNER).count();
        if (owned >= maxOwnedPerAccount) {
            throw new ProviderLimitReachedException(
                    "An account can own at most " + maxOwnedPerAccount + " providers.");
        }
        RateLimitDecision budget = rateLimitService.hit(RateLimitPolicies.PROVIDER_CREATE, accountId.toString(),
                new RateLimitRule(maxCreatedPerAccountPerDay, Duration.ofDays(1)), FailureMode.LOCAL_FALLBACK);
        if (!budget.allowed()) {
            throw new RateLimitedException("PROVIDER_CREATE_RATE_LIMITED",
                    "Too many providers created today. Please try again tomorrow.", budget.retryAfterSeconds());
        }
    }
}
