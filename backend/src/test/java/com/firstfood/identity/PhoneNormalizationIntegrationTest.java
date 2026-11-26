package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PhoneChangeRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import com.firstfood.identity.dto.ProfileResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-13: "+91..." and "91..." are one phone number, so one account. Phones use +91987683xxxx; the database is a
 * shared singleton container, so do not reuse them.
 */
class PhoneNormalizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    AccountLookupService lookup;

    @Autowired
    UserAccountRepository accounts;

    @Test
    void signingUpWithTheBareSpellingAndInWithThePlusSpellingIsTheSameAccount() {
        String bare = "919876830001";
        String plus = "+919876830001";

        AuthTokensResponse created = signUp("customer", bare, "Asha Verma");
        ProfileResponse first = profile(created.accessToken());
        assertThat(first.phone()).isEqualTo(plus); // stored canonical, whatever was typed

        requestOtp(plus);
        AuthTokensResponse again = restClient.post().uri("/api/v1/auth/customer/signin")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(plus, otpSender.lastOtpFor(plus)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(profile(again.accessToken()).accountId()).isEqualTo(first.accountId());
    }

    @Test
    void anotherSpellingOfARegisteredNumberAddsAPortalInsteadOfCreatingASecondAccount() {
        AuthTokensResponse customer = signUp("customer", "+919876830002", "Ravi Kumar");
        AuthTokensResponse staff = signUp("staff", "919876830002", "Ravi Kumar");

        ProfileResponse asCustomer = profile(customer.accessToken());
        ProfileResponse asStaff = profile(staff.accessToken());
        assertThat(asStaff.accountId()).isEqualTo(asCustomer.accountId());
        assertThat(asStaff.portals()).containsExactlyInAnyOrder("CUSTOMER", "STAFF");
    }

    @Test
    void aPhoneChangeToAnotherSpellingOfAnExistingNumberIsRefused() {
        signUp("customer", "+919876830003", "Owner Of Number");
        AuthTokensResponse other = signUp("customer", "+919876830004", "Someone Else");

        restClient.post().uri("/api/v1/me/phone/otp/request")
                .header("Authorization", "Bearer " + other.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeRequest("919876830003"))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Map.class).value(body -> assertThat(body.get("code")).isEqualTo("PHONE_ALREADY_IN_USE"));
    }

    @Test
    void staffLookupFindsTheAccountWhicheverWayTheNumberIsTyped() {
        AuthTokensResponse tokens = signUp("customer", "+919876830005", "Found Person");
        var id = profile(tokens.accessToken()).accountId();

        assertThat(lookup.findActiveAccountIdByPhone("+919876830005")).contains(id);
        assertThat(lookup.findActiveAccountIdByPhone("919876830005")).contains(id);
        assertThat(lookup.findActiveAccountIdByPhone("+91 98768-30005")).contains(id);
        assertThat(lookup.findActiveAccountIdByPhone("+919876830099")).isEmpty();
        assertThat(accounts.findById(id).orElseThrow().getPhone()).isEqualTo("+919876830005");
    }

    // ------------------------------------------------------------ helpers

    private void requestOtp(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Requests a code and signs up using {@code typed} exactly as given (the sender always sees the '+' form). */
    private AuthTokensResponse signUp(String portal, String typed, String name) {
        requestOtp(typed);
        String code = otpSender.lastOtpFor(plusSpelling(typed));
        return restClient.post().uri("/api/v1/auth/" + portal + "/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalSignUpRequest(typed, code, name))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
    }

    private ProfileResponse profile(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }

    /** The expected canonical spelling, written out here on purpose rather than calling the code under test. */
    private static String plusSpelling(String typed) {
        return typed.startsWith("+") ? typed : "+" + typed;
    }
}
