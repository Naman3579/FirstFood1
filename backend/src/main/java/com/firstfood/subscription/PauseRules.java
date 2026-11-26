package com.firstfood.subscription;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

/**
 * The date arithmetic of vacations and provider closures. Pure logic (no Spring, no database) so it is unit-tested
 * directly (rules.md Rule 29.1).
 *
 * <h3>The one date convention</h3>
 * A vacation that starts on S and resumes on R withheld the days S, S+1 ... R-1: start INCLUSIVE, resume EXCLUSIVE.
 * Start 10 Nov, resume 20 Nov = 10 days. A closure from F to T is inclusive on both ends: 15..17 Nov = 3 days.
 *
 * <h3>Never pay for the same date twice</h3>
 * A date can be covered by a closure and by a vacation. Whichever is applied second pays only for the dates the first
 * did not: a resumed vacation subtracts the dates its subscription's closures already covered
 * ({@link #vacationCredit}); a closure subtracts the dates inside vacations that were already resumed
 * ({@link #closureCredit}). The result is the same in either order.
 */
final class PauseRules {

    /** How far ahead a vacation may be scheduled. */
    static final int MAX_SCHEDULE_AHEAD_DAYS = 60;

    private PauseRules() {
    }

    /** An inclusive span of days. */
    record DateRange(LocalDate from, LocalDate to) {

        DateRange {
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("range ends before it starts");
            }
        }

        boolean contains(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(to);
        }

        int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    /** Days a vacation from {@code start} (inclusive) to {@code resumeOn} (exclusive) withheld; never negative. */
    static int pausedDays(LocalDate start, LocalDate resumeOn) {
        return (int) Math.max(0, ChronoUnit.DAYS.between(start, resumeOn));
    }

    /**
     * Days to give back for a vacation: the withheld days minus those a provider closure already compensated.
     *
     * @param closureCovered the dates of the subscription's applied closures ({@code covered_from..covered_to})
     */
    static int vacationCredit(LocalDate start, LocalDate resumeOn, Collection<DateRange> closureCovered) {
        int credit = 0;
        for (LocalDate d = start; d.isBefore(resumeOn); d = d.plusDays(1)) {
            if (!isCovered(d, closureCovered)) {
                credit++;
            }
        }
        return credit;
    }

    /**
     * The part of a closure that falls inside what the subscription is entitled to: the closure's days from the later
     * of the closure start and the subscription start, to the earlier of the closure end and the expiry. Null when
     * nothing falls inside (the subscription starts after the closure, or ended before it).
     */
    static DateRange closureWindow(LocalDate closureFrom, LocalDate closureTo, LocalDate subscriptionStart,
            LocalDate effectiveExpiry) {
        LocalDate from = closureFrom.isAfter(subscriptionStart) ? closureFrom : subscriptionStart;
        LocalDate to = closureTo.isBefore(effectiveExpiry) ? closureTo : effectiveExpiry;
        return to.isBefore(from) ? null : new DateRange(from, to);
    }

    /**
     * Days a closure gives back to one subscription: the days of {@code window} that are not inside a vacation that
     * was already resumed (those were given back by the vacation).
     */
    static int closureCredit(DateRange window, Collection<DateRange> resumedVacations) {
        if (window == null) {
            return 0;
        }
        int credit = 0;
        for (LocalDate d = window.from(); !d.isAfter(window.to()); d = d.plusDays(1)) {
            if (!isCovered(d, resumedVacations)) {
                credit++;
            }
        }
        return credit;
    }

    /** The days a RESUMED vacation withheld, as a range; null when it withheld none. */
    static DateRange withheldRange(LocalDate start, LocalDate resumedOn) {
        return resumedOn.isAfter(start) ? new DateRange(start, resumedOn.minusDays(1)) : null;
    }

    /** Whether a vacation may start on {@code start}; throws the refusal otherwise. */
    static void validateStart(LocalDate start, LocalDate today, LocalDate subscriptionStart, LocalDate effectiveExpiry) {
        if (start.isBefore(today)) {
            throw SubscriptionException.vacationStartInvalid(
                    "A vacation cannot start in the past. Choose today or a later date.");
        }
        if (start.isAfter(today.plusDays(MAX_SCHEDULE_AHEAD_DAYS))) {
            throw SubscriptionException.vacationStartInvalid(
                    "A vacation can be scheduled at most " + MAX_SCHEDULE_AHEAD_DAYS + " days ahead.");
        }
        if (start.isBefore(subscriptionStart)) {
            throw SubscriptionException.vacationStartInvalid(
                    "A vacation cannot start before the subscription does (" + subscriptionStart + ").");
        }
        if (effectiveExpiry != null && start.isAfter(effectiveExpiry)) {
            throw SubscriptionException.vacationStartInvalid(
                    "The subscription ends on " + effectiveExpiry + ", so a vacation cannot start after that.");
        }
    }

    private static boolean isCovered(LocalDate d, Collection<DateRange> ranges) {
        for (DateRange r : ranges) {
            if (r.contains(d)) {
                return true;
            }
        }
        return false;
    }
}
