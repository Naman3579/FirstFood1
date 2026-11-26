package com.firstfood.identity;

import com.firstfood.identity.error.OtpDeliveryException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends OTPs via Brevo's Transactional SMS API
 * (https://developers.brevo.com/docs/transactional-sms-endpoints).
 *
 * Uses {@code type: "transactional"} deliberately, not "marketing" -
 * marketing SMS in Brevo is subject to time-of-day/day-of-week sending
 * restrictions (and expects a [STOP CODE]), which is wrong for a
 * time-sensitive login code.
 *
 * NOT wired up against a real Brevo account - this has been written
 * against Brevo's published API contract but never actually exercised
 * against a live account/phone number from this environment. Verify with
 * a real API key and a real phone number before relying on it in
 * production (memory.md #7).
 */
@Component
@Profile("production")
public class BrevoSmsOtpSender implements OtpSender {

    private static final String SEND_SMS_URL = "https://api.brevo.com/v3/transactionalSMS/send";

    // Bounded waits: OTP requests run inside a database transaction on a request thread, so an unresponsive SMS
    // provider must fail fast rather than pin threads and connections (resilience gate).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String apiKey;
    private final String senderName;
    private final MeterRegistry meterRegistry;

    public BrevoSmsOtpSender(
            RestClient.Builder restClientBuilder,
            @Value("${app.notifications.brevo.api-key}") String apiKey,
            @Value("${app.notifications.brevo.sender-name}") String senderName,
            MeterRegistry meterRegistry) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.apiKey = apiKey;
        this.senderName = senderName;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void send(String phone, String otpCode) {
        try {
            restClient.post()
                    .uri(SEND_SMS_URL)
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(new BrevoSmsRequest(
                            senderName,
                            phone,
                            "Your FirstFood verification code is " + otpCode + ". It expires shortly.",
                            "transactional"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // Count it (alert on a sustained rate) and surface a clean 503. Deliberately NOT logging the exception:
            // it can carry the request URL/body, which contains the phone number and the code.
            meterRegistry.counter("firstfood.otp.delivery.failures", "provider", "brevo").increment();
            throw new OtpDeliveryException();
        }
    }

    private record BrevoSmsRequest(String sender, String recipient, String content, String type) {
    }
}
