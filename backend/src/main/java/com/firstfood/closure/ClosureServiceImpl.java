package com.firstfood.closure;

import com.firstfood.closure.dto.DeclareClosureRequest;
import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MemberSummary;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.provider.ProviderIntake;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.subscription.ClosureCredit;
import com.firstfood.subscription.ClosureCreditService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case orchestration for provider closures.
 *
 * <h3>Declare = one transaction</h3>
 * authorize -> lock the PROVIDER row (lock order everywhere: provider row, then subscription rows) -> check dates ->
 * insert the closure -> if the owner chose to extend, {@link ClosureCreditService#apply} adjusts every eligible
 * subscription. All or nothing: if any subscription cannot be adjusted the closure is not recorded either, so there is
 * never a half-applied closure.
 *
 * <h3>Idempotency</h3>
 * Declaring the same dates with the same decision again returns the recorded closure and writes nothing; the provider
 * row lock serializes two racing submissions. Underneath, {@code closure_no_overlap} refuses a second live closure over
 * the same days, and {@code uq_adjustment_closure_subscription} refuses a second adjustment of one subscription by one
 * closure. A job retry therefore cannot apply a closure twice.
 *
 * Provider scoping: every read and write goes through the provider id from the path after the actor was authorized for
 * THAT provider; the credit service only ever sees that provider's subscriptions.
 */
@Service
class ClosureServiceImpl implements ClosureService {

    /** How far back a closure may be recorded after the fact. */
    static final int MAX_BACKDATE_DAYS = 14;
    /** How far ahead a closure may be announced. */
    static final int MAX_AHEAD_DAYS = 180;
    /** Longest single closure (the database enforces the same limit). */
    static final int MAX_DAYS = 31;

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final MembershipLookupService membershipLookup;
    private final ClosureCreditService credits;
    private final ProviderClosureRepository closures;
    private final BusinessCalendar calendar;
    private final Clock clock;

    ClosureServiceImpl(ProviderAccessService accessService, ProviderLookupService providerLookup,
            MembershipLookupService membershipLookup, ClosureCreditService credits, ProviderClosureRepository closures,
            BusinessCalendar calendar, Clock clock) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.membershipLookup = membershipLookup;
        this.credits = credits;
        this.closures = closures;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ClosurePreview preview(UUID actorAccountId, UUID providerId, LocalDate from, LocalDate to) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        validateDates(from, to, calendar.today());
        List<ClosureCredit> found = credits.preview(providerId, from, to);
        List<ClosureAffected> rows = affected(providerId, found);
        return new ClosurePreview(from, to, (int) ChronoUnit.DAYS.between(from, to) + 1, rows.size(),
                rows.stream().mapToInt(ClosureAffected::days).sum(), rows);
    }

    @Override
    @Transactional
    public DeclareClosureResult declare(UUID actorAccountId, UUID providerId, DeclareClosureRequest request) {
        // Authorize BEFORE locking or touching anything of the provider's.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        if (request.extendSubscriptions() == null) {
            throw ClosureException.decisionRequired();
        }
        LocalDate from = request.startDate();
        LocalDate to = request.endDate();
        validateDates(from, to, calendar.today());

        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw ClosureException.providerClosed();
        }

        Optional<ProviderClosure> existing = closures.findByProviderIdAndStartDateAndEndDateAndStatus(
                providerId, from, to, ClosureStatus.DECLARED);
        if (existing.isPresent()) {
            if (existing.get().isExtendSubscriptions() != request.extendSubscriptions()) {
                throw ClosureException.decisionConflict();
            }
            return new DeclareClosureResult(false, view(existing.get(), true));
        }
        if (!closures.findOverlapping(providerId, from, to).isEmpty()) {
            throw ClosureException.overlaps();
        }

        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        ProviderClosure closure = new ProviderClosure(providerId, from, to, reason, request.extendSubscriptions(),
                actorAccountId);
        try {
            closures.saveAndFlush(closure);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        if (closure.isExtendSubscriptions()) {
            try {
                credits.apply(providerId, closure.getId(), from, to, actorAccountId);
            } catch (DataIntegrityViolationException e) {
                throw translate(e);
            }
        }
        return new DeclareClosureResult(true, view(closure, true));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClosureView> list(UUID actorAccountId, UUID providerId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        return closures.findByProviderIdOrderByStartDateDescCreatedAtDesc(providerId).stream()
                .map(c -> view(c, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ClosureView get(UUID actorAccountId, UUID providerId, UUID closureId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        ProviderClosure closure = closures.findByIdAndProviderId(closureId, providerId)
                .orElseThrow(ClosureException::notFound);
        return view(closure, true);
    }

    @Override
    @Transactional
    public ClosureView cancel(UUID actorAccountId, UUID providerId, UUID closureId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw ClosureException.providerClosed();
        }
        ProviderClosure closure = closures.findByIdAndProviderId(closureId, providerId)
                .orElseThrow(ClosureException::notFound);
        if (closure.getStatus() != ClosureStatus.DECLARED) {
            throw ClosureException.notDeclared();
        }
        if (!credits.appliedBy(closureId).isEmpty()) {
            throw ClosureException.hasExtensions();
        }
        closure.cancel(actorAccountId, clock.instant());
        try {
            closures.saveAndFlush(closure);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        return view(closure, true);
    }

    // ================================================================ helpers

    /** One definition of a valid closure span, shared by preview and declare so a preview never promises what declare refuses. */
    private static void validateDates(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null) {
            throw ClosureException.datesInvalid("Choose the first and last day of the closure.");
        }
        if (to.isBefore(from)) {
            throw ClosureException.datesInvalid("The last day cannot be before the first day.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ClosureException.datesInvalid("A closure can cover at most " + MAX_DAYS
                    + " days. Declare a longer closure in separate parts.");
        }
        if (from.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw ClosureException.datesInvalid(
                    "A closure cannot start more than " + MAX_BACKDATE_DAYS + " days in the past.");
        }
        if (from.isAfter(today.plusDays(MAX_AHEAD_DAYS))) {
            throw ClosureException.datesInvalid(
                    "A closure cannot start more than " + MAX_AHEAD_DAYS + " days ahead.");
        }
    }

    private ClosureView view(ProviderClosure c, boolean withRows) {
        List<ClosureCredit> applied = c.isExtendSubscriptions() ? credits.appliedBy(c.getId()) : List.of();
        List<ClosureAffected> rows = withRows ? affected(c.getProviderId(), applied) : List.of();
        String processing = c.getStatus() == ClosureStatus.CANCELLED ? "CANCELLED"
                : c.isExtendSubscriptions() ? "COMPLETED" : "NOT_REQUESTED";
        return new ClosureView(c.getId(), c.getProviderId(), c.getStartDate(), c.getEndDate(), c.days(), c.getReason(),
                c.isExtendSubscriptions(), c.getStatus(), processing, c.getCreatedBy(), c.getCreatedAt(),
                c.getCancelledAt(), applied.size(), applied.stream().mapToInt(ClosureCredit::days).sum(), rows);
    }

    private List<ClosureAffected> affected(UUID providerId, List<ClosureCredit> credits) {
        if (credits.isEmpty()) {
            return List.of();
        }
        Set<UUID> membershipIds = new HashSet<>();
        credits.forEach(c -> membershipIds.add(c.membershipId()));
        Map<UUID, MemberSummary> names = membershipLookup.summarize(membershipIds);
        return credits.stream().map(c -> {
            MemberSummary m = names.get(c.membershipId());
            return new ClosureAffected(c.subscriptionId(), c.membershipId(), m == null ? "(unknown)" : m.fullName(),
                    c.planName(), c.previousExpiryDate(), c.newExpiryDate(), c.days());
        }).toList();
    }

    private static ClosureException translate(DataIntegrityViolationException e) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage());
        if (detail.contains("closure_no_overlap")) {
            return ClosureException.overlaps();
        }
        if (detail.contains("already extended")) {
            return ClosureException.hasExtensions();
        }
        if (detail.contains("uq_adjustment_closure_subscription") || detail.contains("currently expires on")
                || detail.contains("can no longer change")) {
            return ClosureException.concurrentChange();
        }
        throw e;
    }
}
