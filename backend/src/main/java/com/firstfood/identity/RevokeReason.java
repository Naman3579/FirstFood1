package com.firstfood.identity;

/** Why a refresh session stopped being usable (column {@code refresh_session.revoked_reason}, F-11). */
public enum RevokeReason {
    /** Spent by a normal refresh. Presenting it again later means two parties hold the token: treated as theft. */
    ROTATED,
    /** The user signed out. */
    LOGOUT,
    /** Revoked because a rotated token was replayed (the whole family goes). */
    REUSE,
    /** The account's phone number changed; every other session was ended. */
    PHONE_CHANGE,
    /** Revoked before families existed; cause unknown, never a theft signal. */
    LEGACY
}
