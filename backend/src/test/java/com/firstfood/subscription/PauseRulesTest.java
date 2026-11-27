package com.firstfood.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.subscription.PauseRules.DateRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The date arithmetic of vacations and provider closures with no Spring and no database (rules.md Rule 29.1): the one
 * date convention (vacation start inclusive, resume exclusive; closure inclusive both ends) and the rule that a date is
 * never paid for twice, whichever of the two is applied first.
 */
class PauseRulesTest {

    private static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
    private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);

    private static DateRange range(int fromDay, int toDay) {
        return new DateRange(LocalDate.of(2026, 11, fromDay), LocalDate.of(2026, 11, toDay));
    }

    // ------------------------------------------------------------ the convention

    @Test
    void aVacationFromThe10thResumingOnThe20thWithheldTenDays() {
        assertThat(PauseRules.pausedDays(NOV_10, NOV_20)).isEqualTo(10);
    }

    @Test
    void resumingOnTheStartDayWithheldNothingAndNeverGoesNegative() {
        assertThat(PauseRules.pausedDays(NOV_10, NOV_10)).isZero();
        assertThat(PauseRules.pausedDays(NOV_20, NOV_10)).isZero();
    }

    @Test
    void aClosureIsInclusiveOnBothEnds() {
        assertThat(range(15, 17).days()).isEqualTo(3);
        assertThat(range(15, 15).days()).isEqualTo(1);
        assertThatThrownBy(() -> range(17, 15)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWithheldRangeOfAResumedVacationEndsTheDayBeforeResuming() {
        assertThat(PauseRules.withheldRange(NOV_10, NOV_20)).isEqualTo(range(10, 19));
        assertThat(PauseRules.withheldRange(NOV_10, NOV_10)).isNull();
    }

    // ------------------------------------------------------------ never pay twice

    @Test
    void aVacationWithNoClosureGivesBackEveryWithheldDay() {
        assertThat(PauseRules.vacationCredit(NOV_10, NOV_20, List.of())).isEqualTo(10);
    }

    @Test
    void aVacationDoesNotGiveBackDaysAClosureAlreadyCompensated() {
        // closure 15..17 (3 days) already extended this subscription; the vacation 10..19 gives back only the other 7
        assertThat(PauseRules.vacationCredit(NOV_10, NOV_20, List.of(range(15, 17)))).isEqualTo(7);
    }

    @Test
    void aClosureOutsideTheVacationChangesNothing() {
        assertThat(PauseRules.vacationCredit(NOV_10, NOV_20, List.of(range(25, 27)))).isEqualTo(10);
    }

    @Test
    void aClosureCoveringTheWholeVacationLeavesNothingToGiveBack() {
        assertThat(PauseRules.vacationCredit(NOV_10, NOV_20, List.of(range(1, 30)))).isZero();
    }

    @Test
    void aClosureDoesNotPayForDaysAnAlreadyResumedVacationGaveBack() {
        DateRange window = range(15, 17);
        assertThat(PauseRules.closureCredit(window, List.of(range(10, 19)))).isZero();
        assertThat(PauseRules.closureCredit(window, List.of(range(16, 16)))).isEqualTo(2);
        assertThat(PauseRules.closureCredit(window, List.of())).isEqualTo(3);
    }

    @Test
    void theTotalIsTheSameWhicheverIsAppliedFirst() {
        // vacation 10..19 withheld, closure 15..17: the customer is owed the UNION of the two, 10 days, either way.
        DateRange vacation = range(10, 19);
        DateRange closure = range(15, 17);
        int closureFirst = PauseRules.closureCredit(closure, List.of())
                + PauseRules.vacationCredit(NOV_10, NOV_20, List.of(closure));
        int vacationFirst = PauseRules.vacationCredit(NOV_10, NOV_20, List.of())
                + PauseRules.closureCredit(closure, List.of(vacation));
        assertThat(closureFirst).isEqualTo(10).isEqualTo(vacationFirst);
    }

    // ------------------------------------------------------------ which part of a closure a subscription is owed

    @Test
    void aClosureIsClippedToTheSubscriptionsEntitlement() {
        LocalDate start = LocalDate.of(2026, 11, 16);
        LocalDate expiry = LocalDate.of(2026, 11, 16);
        // starts on the 16th: owed only the 16th and 17th of a 15..17 closure; expiring on the 16th: only the 16th
        assertThat(PauseRules.closureWindow(range(15, 17).from(), range(15, 17).to(), start, LocalDate.of(2026, 11, 30)))
                .isEqualTo(range(16, 17));
        assertThat(PauseRules.closureWindow(range(15, 17).from(), range(15, 17).to(), start, expiry))
                .isEqualTo(range(16, 16));
    }

    @Test
    void aSubscriptionOutsideTheClosureIsOwedNothing() {
        assertThat(PauseRules.closureWindow(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 17),
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 12, 18))).isNull(); // starts after
        assertThat(PauseRules.closureWindow(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 17),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 14))).isNull(); // ended before
        assertThat(PauseRules.closureCredit(null, List.of())).isZero();
    }

    // ------------------------------------------------------------ when a vacation may start

    @Test
    void aVacationMayStartTodayOrLaterWithinTheSubscriptionAndTheScheduleWindow() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        LocalDate subStart = LocalDate.of(2026, 11, 1);
        LocalDate expiry = LocalDate.of(2026, 12, 31);
        assertThatCode(() -> PauseRules.validateStart(today, today, subStart, expiry)).doesNotThrowAnyException();
        assertThatCode(() -> PauseRules.validateStart(today.plusDays(PauseRules.MAX_SCHEDULE_AHEAD_DAYS), today, subStart,
                null)).doesNotThrowAnyException();
    }

    @Test
    void aVacationCannotStartInThePastTooFarAheadBeforeTheSubscriptionOrAfterItsEnd() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        LocalDate subStart = LocalDate.of(2026, 11, 12);
        LocalDate expiry = LocalDate.of(2026, 11, 30);
        assertThatThrownBy(() -> PauseRules.validateStart(today.minusDays(1), today, subStart, expiry))
                .isInstanceOf(SubscriptionException.class);
        assertThatThrownBy(() -> PauseRules.validateStart(today.plusDays(PauseRules.MAX_SCHEDULE_AHEAD_DAYS + 1), today,
                subStart, null)).isInstanceOf(SubscriptionException.class);
        assertThatThrownBy(() -> PauseRules.validateStart(today, today, subStart, expiry)) // before the subscription starts
                .isInstanceOf(SubscriptionException.class);
        assertThatThrownBy(() -> PauseRules.validateStart(LocalDate.of(2026, 12, 1), today, subStart, expiry))
                .isInstanceOf(SubscriptionException.class);
    }
}
