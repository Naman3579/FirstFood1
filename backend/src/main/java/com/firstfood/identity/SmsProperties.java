package com.firstfood.identity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.sms.*} - F-05 (SMS pumping). Every OTP SMS costs money, so what may be texted and how much may be texted
 * per day are configuration, not code.
 *
 * @param allowedCountryCodes country calling codes (digits only, no '+') OTP SMS may be sent to. The pilot is India
 *                            only, so the default is {@code 91}. Adding a code here is a deliberate business decision:
 *                            also allow that country in the Brevo account (see docs/OPERATIONS.md).
 * @param dailyBudget         hard cap on OTP SMS per business-calendar day, across ALL users. Reaching it opens the
 *                            circuit breaker until the next day (or until an operator raises the cap).
 * @param hourlyBudget        hard cap on OTP SMS in any rolling hour, across all users. Trips earlier than the daily
 *                            cap when a burst starts, so a flood cannot burn the whole day's budget in minutes.
 * @param alertPercent        percentage of the daily budget at which an alert (WARN log + metric) fires once.
 */
@ConfigurationProperties(prefix = "app.sms")
public record SmsProperties(List<String> allowedCountryCodes, int dailyBudget, int hourlyBudget, int alertPercent) {

    public SmsProperties {
        allowedCountryCodes = allowedCountryCodes == null || allowedCountryCodes.isEmpty()
                ? List.of("91")
                : allowedCountryCodes.stream().map(SmsProperties::cleanCode).toList();
        if (dailyBudget < 1) {
            throw new IllegalArgumentException("app.sms.daily-budget must be >= 1, got " + dailyBudget);
        }
        if (hourlyBudget < 1) {
            throw new IllegalArgumentException("app.sms.hourly-budget must be >= 1, got " + hourlyBudget);
        }
        if (alertPercent < 1 || alertPercent > 100) {
            throw new IllegalArgumentException("app.sms.alert-percent must be 1-100, got " + alertPercent);
        }
    }

    private static String cleanCode(String code) {
        String digits = code == null ? "" : code.trim().replaceFirst("^\\+", "");
        if (!digits.matches("[1-9]\\d{0,2}")) {
            throw new IllegalArgumentException("app.sms.allowed-country-codes: not a country calling code: '" + code + "'");
        }
        return digits;
    }
}
