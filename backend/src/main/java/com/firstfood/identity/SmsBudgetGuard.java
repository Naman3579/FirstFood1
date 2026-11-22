package com.firstfood.identity;

import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitRule;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.identity.error.SmsBudgetExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Site-wide spend control for OTP SMS (F-05): the last line of defence when per-phone and per-IP limits are beaten by
 * an attacker who rotates both. Two global counters, shared by every backend replica through Redis:
 * <ul>
 *   <li><b>hourly</b> (sliding window) - trips within minutes of a burst, so a flood cannot burn the whole day;</li>
 *   <li><b>daily</b> (business-calendar day, Asia/Kolkata) - the hard ceiling on what can be spent in one day.</li>
 * </ul>
 * Either one spent means the CIRCUIT IS OPEN: {@link #reserve()} throws 503 {@code SMS_TEMPORARILY_UNAVAILABLE} and
 * nothing is sent until the window reopens. Crossing {@code alert-percent} of the daily budget raises an ALERT (one
 * WARN log + {@code firstfood.sms.budget.alerts}); an open circuit is {@code firstfood.sms.budget.rejected} and a
 * throttled ERROR log. Alert on both (docs/OPERATIONS.md).
 *
 * <p>Trade-off, stated plainly: a determined attacker can still spend the budget and so deny SMS login to real users
 * for the rest of the window. That is the intended failure: a bounded, visible outage instead of an unbounded bill.
 * The bot challenge and country allow-list make it expensive to get there. Fails CLOSED if Redis is down.
 *
 * <p>Call once per SMS actually about to be sent, AFTER the per-phone limit, so a flood aimed at one number is stopped
 * by that number's own limit before it touches the shared budget.
 */
@Component
public class SmsBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(SmsBudgetGuard.class);
    private static final Duration HOUR = Duration.ofHours(1);
    // The daily key is named by calendar date and expires on its own; the extra hours cover the day-end and clock skew.
    private static final Duration DAILY_KEY_TTL = Duration.ofHours(26);
    private static final long OPEN_LOG_INTERVAL_MILLIS = 60_000;

    private final RateLimitService rateLimitService;
    private final BusinessCalendar calendar;
    private final SmsProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastOpenLogMillis = new AtomicLong(0);

    public SmsBudgetGuard(
            RateLimitService rateLimitService,
            BusinessCalendar calendar,
            SmsProperties properties,
            MeterRegistry meterRegistry) {
        this.rateLimitService = rateLimitService;
        this.calendar = calendar;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** Takes one SMS from the budget, or throws {@link SmsBudgetExceededException} (503) if the circuit is open. */
    public void reserve() {
        // Hourly first: a denied sliding hit is not recorded, so a blocked burst does not also eat the daily budget.
        RateLimitDecision hourly = rateLimitService.hit(RateLimitPolicies.SMS_GLOBAL_HOURLY, "global",
                new RateLimitRule(properties.hourlyBudget(), HOUR), FailureMode.FAIL_CLOSED);
        if (!hourly.allowed()) {
            reject("hourly", hourly);
        }

        RateLimitDecision daily = rateLimitService.hit(RateLimitPolicies.SMS_GLOBAL_DAILY,
                calendar.today().toString(),
                new RateLimitRule(properties.dailyBudget(), DAILY_KEY_TTL), FailureMode.FAIL_CLOSED);
        if (!daily.allowed()) {
            reject("daily", daily);
        }

        meterRegistry.counter("firstfood.sms.budget.reserved").increment();
        if (daily.count() == alertThreshold()) {
            // Counts only ever go up inside a day, so equality fires exactly once per day.
            meterRegistry.counter("firstfood.sms.budget.alerts", "scope", "daily").increment();
            log.warn("SMS budget alert: {} of {} OTP SMS used today ({}%). Check for SMS pumping "
                    + "(firstfood.ratelimit.decisions, firstfood.sms.budget.*).",
                    daily.count(), properties.dailyBudget(), properties.alertPercent());
        }
    }

    private void reject(String scope, RateLimitDecision decision) {
        meterRegistry.counter("firstfood.sms.budget.rejected", "scope", scope).increment();
        long now = System.currentTimeMillis();
        long last = lastOpenLogMillis.get();
        if (now - last >= OPEN_LOG_INTERVAL_MILLIS && lastOpenLogMillis.compareAndSet(last, now)) {
            log.error("SMS circuit breaker OPEN ({} budget spent): OTP SMS are refused until it resets. "
                    + "If this is not real traffic it is SMS pumping - see docs/OPERATIONS.md.", scope);
        }
        throw new SmsBudgetExceededException(decision.retryAfterSeconds());
    }

    private long alertThreshold() {
        return Math.max(1, (long) Math.ceil(properties.dailyBudget() * properties.alertPercent() / 100.0));
    }
}
