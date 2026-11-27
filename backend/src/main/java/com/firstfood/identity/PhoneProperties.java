package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.phone.*}.
 *
 * @param verificationRequired false (launch default, while SMS/DLT is not set up): an account may ADD a phone number
 *                             without a code ({@code PUT /me/phone}), and customer-facing features (invitations from a
 *                             provider, the customer list) accept such a number. true: a number must be proven with a
 *                             code - adding one without verification is refused and unverified numbers are ignored
 *                             everywhere. Staff roles, ownership offers and phone sign-in need a VERIFIED number in
 *                             both modes.
 */
@ConfigurationProperties(prefix = "app.phone")
public record PhoneProperties(boolean verificationRequired) {
}
