package com.firstfood.membership;

/** PENDING until the invited person answers; EXPIRED is set by the system, WITHDRAWN by the inviting provider. */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    WITHDRAWN,
    EXPIRED
}
