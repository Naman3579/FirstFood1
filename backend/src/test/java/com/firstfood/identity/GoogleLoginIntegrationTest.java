package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.GoogleCredentialRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.error.GoogleSignInUnavailableException;
import com.firstfood.identity.error.InvalidGoogleTokenException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Google sign-up and sign-in (POST /auth/{portal}/google/signup|signin). The ID-token check itself is covered by
 * GoogleIdTokenVerifierTest; here the verifier is a mock that "proves" an identity, so these tests exercise the account
 * rules. Emails are unique to this class: the database is a shared singleton container.
 */
class GoogleLoginIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    GoogleIdTokenVerifier verifier;

    @Autowired
    RestTestClient restClient;

    private void proves(String credential, String email, String name) {
        when(verifier.verify(credential)).thenReturn(new GoogleIdentity("sub-" + email, email, name));
    }

    @Test
    void signUpCreatesAnEmailOnlyAccountNamedFromGoogleAndSignInReturnsToIt() {
        proves("cred-a", "google.ananya@example.com", "Ananya Rao");

        AuthTokensResponse tokens = post("customer", "signup", "cred-a").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        ProfileResponse me = profile(tokens.accessToken());
        assertThat(me.email()).isEqualTo("google.ananya@example.com");
        assertThat(me.emailVerified()).isTrue();
        assertThat(me.phone()).isNull();
        assertThat(me.fullName()).isEqualTo("Ananya Rao");
        assertThat(me.portals()).containsExactly("CUSTOMER");

        AuthTokensResponse again = post("customer", "signin", "cred-a").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(profile(again.accessToken()).accountId()).isEqualTo(me.accountId());
    }

    @Test
    void signInWithoutAnAccountIs404AndSignUpTwiceIs409() {
        proves("cred-b", "google.new@example.com", "New Person");
        assertCode(post("customer", "signin", "cred-b"), HttpStatus.NOT_FOUND, "ACCOUNT_NOT_REGISTERED");

        post("customer", "signup", "cred-b").expectStatus().isOk();
        assertCode(post("customer", "signup", "cred-b"), HttpStatus.CONFLICT, "ACCOUNT_ALREADY_REGISTERED");
    }

    @Test
    void signingUpOnTheOtherPortalAddsItToTheSameAccount() {
        proves("cred-c", "google.both@example.com", "Both Portals");
        AuthTokensResponse customer = post("customer", "signup", "cred-c").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        // Not yet on the staff portal.
        assertCode(post("staff", "signin", "cred-c"), HttpStatus.NOT_FOUND, "ACCOUNT_NOT_REGISTERED");

        AuthTokensResponse staff = post("staff", "signup", "cred-c").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        ProfileResponse me = profile(staff.accessToken());
        assertThat(me.accountId()).isEqualTo(profile(customer.accessToken()).accountId());
        assertThat(me.portals()).containsExactly("CUSTOMER", "STAFF");
    }

    @Test
    void anInvalidCredentialIs401() {
        when(verifier.verify(anyString())).thenThrow(new InvalidGoogleTokenException());
        assertCode(post("customer", "signin", "forged"), HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN");
        assertCode(post("customer", "signup", "forged"), HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void whenGoogleSignInIsNotConfiguredTheEndpointsAre503() {
        when(verifier.verify(anyString())).thenThrow(new GoogleSignInUnavailableException());
        assertCode(post("customer", "signin", "anything"), HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_SIGNIN_UNAVAILABLE");
    }

    @Test
    void aBlankCredentialIs400() {
        post("customer", "signin", "").expectStatus().isBadRequest();
    }

    private RestTestClient.ResponseSpec post(String portal, String action, String credential) {
        return restClient.post().uri("/api/v1/auth/" + portal + "/google/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new GoogleCredentialRequest(credential))
                .exchange();
    }

    private ProfileResponse profile(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }

    private static void assertCode(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        Map<?, ?> body = spec.expectStatus().isEqualTo(status).expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
    }
}
