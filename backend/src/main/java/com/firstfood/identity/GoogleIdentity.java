package com.firstfood.identity;

/**
 * What a verified Google ID token proves. {@code email} is already lower-cased and Google has confirmed the person owns
 * it ({@code email_verified}), which is the same assurance an emailed one-time code gives.
 *
 * @param subject Google's stable user id ({@code sub}); never reused, unlike an email address.
 * @param name    the display name on the Google profile; may be null.
 */
public record GoogleIdentity(String subject, String email, String name) {
}
