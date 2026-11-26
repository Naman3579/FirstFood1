package com.firstfood.subscription;

import com.firstfood.common.time.BusinessCalendar;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionLookupServiceImpl implements SubscriptionLookupService {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionTermSnapshotRepository snapshots;
    private final BusinessCalendar calendar;
    private final SubscriptionExpiryAdjustmentRepository adjustments;
    private final SubscriptionPauseRepository pauses;

    public SubscriptionLookupServiceImpl(SubscriptionRepository subscriptions,
            SubscriptionTermSnapshotRepository snapshots, BusinessCalendar calendar,
            SubscriptionExpiryAdjustmentRepository adjustments, SubscriptionPauseRepository pauses) {
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.calendar = calendar;
        this.adjustments = adjustments;
        this.pauses = pauses;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionRef> find(UUID providerId, UUID subscriptionId) {
        return subscriptions.findByIdAndProviderId(subscriptionId, providerId).map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionRef> findOwned(Collection<UUID> personIds, UUID subscriptionId) {
        if (personIds == null || personIds.isEmpty()) {
            return Optional.empty();
        }
        return subscriptions.findByIdAndPersonIdIn(subscriptionId, personIds)
                .map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SubscriptionRef> lock(UUID providerId, UUID subscriptionId) {
        return subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SubscriptionRef> lockOwned(Collection<UUID> personIds, UUID subscriptionId) {
        if (personIds == null || personIds.isEmpty()) {
            return Optional.empty();
        }
        return subscriptions.findByIdAndPersonIdInForUpdate(subscriptionId, personIds)
                .map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionPrice> priceOf(UUID subscriptionId) {
        return snapshots.findBySubscriptionIdIn(List.of(subscriptionId)).stream()
                .findFirst()
                .map(t -> new SubscriptionPrice(t.getPrice(), t.getCurrency()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionRef> findDayEntitledOn(UUID providerId, LocalDate date) {
        // Cancelled subscriptions count up to (not including) the day they were cancelled.
        return toRefs(subscriptions.findDayEntitledOn(providerId, date, calendar.startOfDay(date.plusDays(1))));
    }

    private List<SubscriptionRef> toRefs(List<Subscription> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, SubscriptionTermSnapshot> terms = new HashMap<>();
        snapshots.findBySubscriptionIdIn(found.stream().map(Subscription::getId).toList())
                .forEach(t -> terms.put(t.getSubscriptionId(), t));
        List<UUID> ids = found.stream().map(Subscription::getId).toList();
        Set<UUID> renewed = new HashSet<>(subscriptions.findRenewedIds(ids));
        // V25: days given back by a resumed vacation or an applied closure are NOT absence extensions. The absence engine
        // therefore sees the base expiry and the calendar-window cap shifted by them (the same shift V25's database guard
        // applies), so a closure neither counts as extension already used nor eats the headroom an absent customer earned.
        Map<UUID, Long> adjusted = new HashMap<>();
        adjustments.totalsFor(ids).forEach(t -> adjusted.put(t.subscriptionId(), t.days()));
        Map<UUID, List<SubscriptionRef.VacationSpan>> vacations = new HashMap<>();
        pauses.findBySubscriptionIdInAndStatusNotOrderByStartDateAsc(ids, PauseStatus.CANCELLED).forEach(p ->
                vacations.computeIfAbsent(p.getSubscriptionId(), k -> new java.util.ArrayList<>())
                        .add(new SubscriptionRef.VacationSpan(p.getStartDate(), p.getResumedOn())));
        return found.stream().map(s -> {
            SubscriptionTermSnapshot t = terms.get(s.getId());
            int shift = adjusted.getOrDefault(s.getId(), 0L).intValue();
            LocalDate max = SubscriptionRules.maximumExpiry(s.getStartDate(), t.getMaxCalendarWindowDays());
            return new SubscriptionRef(s.getId(), s.getProviderId(), s.getMembershipId(), s.getPersonId(),
                    s.getConsumptionType(), s.getStatus(), s.getStartDate(),
                    s.getBaseExpiryDate() == null ? null : s.getBaseExpiryDate().plusDays(shift),
                    s.getEffectiveExpiryDate(), max == null ? null : max.plusDays(shift),
                    s.getCancelledAt(), t.getPlanName(), t.isSameDayAbsenceAllowed(), t.getAbsenceCutoffTime(),
                    t.isExtensionAllowed(), t.getMinConsecutiveAbsenceDays(), renewed.contains(s.getId()),
                    s.getPausedSince(), vacations.getOrDefault(s.getId(), List.of()));
        }).toList();
    }
}
