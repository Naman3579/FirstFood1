package com.firstfood.identity;

/** Turns the {@code credential} Google Identity Services hands the browser into a proven identity, or throws. */
public interface GoogleIdTokenVerifier {

    /**
     * @throws com.firstfood.identity.error.GoogleSignInUnavailableException when Google sign-in is not configured, or
     *         Google's signing keys cannot be fetched (503)
     * @throws com.firstfood.identity.error.InvalidGoogleTokenException for anything that is not a currently valid ID
     *         token for this app's client with a verified email (401)
     */
    GoogleIdentity verify(String idToken);
}
