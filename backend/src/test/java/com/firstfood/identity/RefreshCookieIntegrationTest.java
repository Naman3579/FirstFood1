package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The HttpOnly-cookie refresh transport (X-Refresh-Mode: cookie). Body mode is covered by
 * AuthenticationFlowIntegrationTest and must stay unchanged. Phones are +91987662xxxx.
 */
class RefreshCookieIntegrationTest extends AbstractIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000"; // application.yml default
    private static final String COOKIE = "ff_refresh";

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void loginSetsAnHttpOnlyStrictCookieAndKeepsTheRefreshTokenOutOfTheBody() {
        var result = login("+919876620001");

        assertThat(result.setCookie())
                .startsWith(COOKIE + "=")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=");
        assertThat(result.body()).contains("accessToken").doesNotContain("\"refreshToken\":\"");
    }

    @Test
    void refreshRotatesTheCookieAndTheOldOneStopsWorking() {
        String cookie = login("+919876620002").cookieValue();

        var refreshed = refresh(cookie, ALLOWED_ORIGIN)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult();
        String rotated = valueOf(refreshed.getResponseHeaders().getFirst("Set-Cookie"));

        assertThat(rotated).isNotBlank().isNotEqualTo(cookie);
        // Replaying the previous cookie must fail (rotate-on-use).
        refresh(cookie, ALLOWED_ORIGIN).expectStatus().isUnauthorized();
        refresh(rotated, ALLOWED_ORIGIN).expectStatus().isOk();
    }

    @Test
    void aRequestFromAnotherOriginIsRefusedEvenWithAValidCookie() {
        String cookie = login("+919876620003").cookieValue();

        refresh(cookie, "https://evil.example").expectStatus().isForbidden();
        // No Origin at all is refused too: browsers always send one on POST, scripts using cookie mode have no reason not to.
        restClient.post().uri("/api/v1/auth/refresh")
                .header("X-Refresh-Mode", "cookie")
                .header("Cookie", COOKIE + "=" + cookie)
                .exchange().expectStatus().isForbidden();
        // The refused attempts did not spend the token.
        refresh(cookie, ALLOWED_ORIGIN).expectStatus().isOk();
    }

    @Test
    void theCookieIsIgnoredUnlessTheClientOptsIn() {
        String cookie = login("+919876620004").cookieValue();

        // No X-Refresh-Mode header and no body: the cookie alone authorises nothing.
        restClient.post().uri("/api/v1/auth/refresh")
                .header("Cookie", COOKIE + "=" + cookie)
                .header("Origin", ALLOWED_ORIGIN)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void refreshWithoutACookieIs401() {
        restClient.post().uri("/api/v1/auth/refresh")
                .header("X-Refresh-Mode", "cookie")
                .header("Origin", ALLOWED_ORIGIN)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() {
        String cookie = login("+919876620005").cookieValue();

        var logout = restClient.post().uri("/api/v1/auth/logout")
                .header("X-Refresh-Mode", "cookie")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Cookie", COOKIE + "=" + cookie)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody(String.class).returnResult();

        assertThat(logout.getResponseHeaders().getFirst("Set-Cookie")).contains("Max-Age=0");
        refresh(cookie, ALLOWED_ORIGIN).expectStatus().isUnauthorized();
    }

    @Test
    void logoutIsIdempotentWithoutACookie() {
        restClient.post().uri("/api/v1/auth/logout")
                .header("X-Refresh-Mode", "cookie")
                .header("Origin", ALLOWED_ORIGIN)
                .exchange().expectStatus().isNoContent();
    }

    // ---------------------------------------------------------------- helpers

    private record Login(String setCookie, String body) {
        String cookieValue() {
            return valueOf(setCookie);
        }
    }

    private static String valueOf(String setCookieHeader) {
        String firstPair = setCookieHeader.split(";", 2)[0];
        return firstPair.substring(firstPair.indexOf('=') + 1);
    }

    private Login login(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        var result = restClient.post().uri("/api/v1/auth/otp/verify")
                .header("X-Refresh-Mode", "cookie")
                .header("Origin", ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult();
        return new Login(result.getResponseHeaders().getFirst("Set-Cookie"), result.getResponseBody());
    }

    private RestTestClient.ResponseSpec refresh(String cookieValue, String origin) {
        return restClient.post().uri("/api/v1/auth/refresh")
                .header("X-Refresh-Mode", "cookie")
                .header("Origin", origin)
                .header("Cookie", COOKIE + "=" + cookieValue)
                .exchange();
    }
}
