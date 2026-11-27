package com.firstfood.identity;

/**
 * Delivers a short security notice (never a secret) to a phone. Same rule as {@link OtpSender}: business logic depends
 * on this interface, not on a vendor SDK.
 */
public interface SecurityNoticeSender {

    void send(String phone, String message);
}
