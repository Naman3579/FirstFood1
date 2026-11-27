package com.firstfood.common.ratelimit;

/** Stable policy names: they are metric tags and the middle part of every Redis key. Renaming resets counters. */
public final class RateLimitPolicies {

    public static final String OTP_REQUEST_PHONE = "otp-request-phone";
    public static final String OTP_REQUEST_IP_BURST = "otp-request-ip-burst";
    public static final String OTP_REQUEST_IP_HOURLY = "otp-request-ip-hourly";
    public static final String OTP_VERIFY_IP = "otp-verify-ip";
    public static final String OTP_VERIFY_FAILURES_PHONE_IP = "otp-verify-failures-phone-ip";
    public static final String OTP_VERIFY_FAILURES_PHONE = "otp-verify-failures-phone";
    public static final String AUTH_IP = "auth-ip";
    public static final String REFRESH_PER_TOKEN = "refresh-per-token";
    public static final String API_IP = "api-ip";
    public static final String ACCOUNT_READ = "account-read";
    public static final String ACCOUNT_WRITE = "account-write";
    /** F-03: per provider, new customer invitations per day (each one probes a phone number). */
    public static final String PROVIDER_INVITE = "provider-invite";
    /** F-03: per provider, phone-number-to-account lookups for role assignment / ownership transfer per day. */
    public static final String PROVIDER_PHONE_LOOKUP = "provider-phone-lookup";
    /** F-03: per account, providers created per day. */
    public static final String PROVIDER_CREATE = "provider-create";

    /** F-05: site-wide OTP SMS in any rolling hour (sliding window). One shared key, across all users and replicas. */
    public static final String SMS_GLOBAL_HOURLY = "sms-global-hourly";
    /** F-05: site-wide OTP SMS per business-calendar day (the key is the date, so each day starts at zero). */
    public static final String SMS_GLOBAL_DAILY = "sms-global-daily";

    private RateLimitPolicies() {
    }
}
