package com.firstfood.identity;

public enum OtpPurpose {
    LOGIN,
    PHONE_CHANGE,
    EMAIL_CHANGE,
    /** Step-up proof before an owner-level action (transfer ownership, close a provider). F-08. */
    OWNER_ACTION
}
