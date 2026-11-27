package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RouteClassTest {

    @Test
    void smsSendingRoutesAreTheStrictClass() {
        assertThat(RouteClass.classify("POST", "/api/v1/auth/otp/request")).isEqualTo(RouteClass.OTP_REQUEST);
        assertThat(RouteClass.classify("POST", "/api/v1/me/phone/otp/request")).isEqualTo(RouteClass.OTP_REQUEST);
    }

    @Test
    void emailSendingRouteIsAlsoTheStrictClass() {
        assertThat(RouteClass.classify("POST", "/api/v1/auth/email/otp/request")).isEqualTo(RouteClass.OTP_REQUEST);
    }

    @Test
    void emailSignInAndSignUpSpendTheVerifyBudget() {
        assertThat(RouteClass.classify("POST", "/api/v1/auth/customer/email/signin")).isEqualTo(RouteClass.OTP_VERIFY);
        assertThat(RouteClass.classify("POST", "/api/v1/auth/staff/email/signup")).isEqualTo(RouteClass.OTP_VERIFY);
        assertThat(RouteClass.classify("POST", "/api/v1/auth/customer/email/other")).isEqualTo(RouteClass.AUTH_TOKEN);
    }

    @Test
    void codeGuessingRoutesAreTheirOwnClass() {
        assertThat(RouteClass.classify("POST", "/api/v1/auth/otp/verify")).isEqualTo(RouteClass.OTP_VERIFY);
        assertThat(RouteClass.classify("POST", "/api/v1/me/phone/otp/verify")).isEqualTo(RouteClass.OTP_VERIFY);
    }

    @Test
    void otherAuthRoutesAreTokenRoutes() {
        assertThat(RouteClass.classify("POST", "/api/v1/auth/refresh")).isEqualTo(RouteClass.AUTH_TOKEN);
        assertThat(RouteClass.classify("POST", "/api/v1/auth/logout")).isEqualTo(RouteClass.AUTH_TOKEN);
        // Only POST sends an SMS, so a GET on the same path is not the SMS class.
        assertThat(RouteClass.classify("GET", "/api/v1/auth/otp/request")).isEqualTo(RouteClass.AUTH_TOKEN);
    }

    @Test
    void everythingElseIsSplitByMethodSafety() {
        assertThat(RouteClass.classify("GET", "/api/v1/providers")).isEqualTo(RouteClass.READ);
        assertThat(RouteClass.classify("PATCH", "/api/v1/providers/1")).isEqualTo(RouteClass.WRITE);
        assertThat(RouteClass.classify("DELETE", "/api/v1/providers/1/roles/2")).isEqualTo(RouteClass.WRITE);
    }

    @Test
    void probesAreExempt() {
        assertThat(RouteClass.classify("GET", "/actuator/health")).isEqualTo(RouteClass.EXEMPT);
        assertThat(RouteClass.classify("GET", "/api/v1/version")).isEqualTo(RouteClass.EXEMPT);
    }
}
