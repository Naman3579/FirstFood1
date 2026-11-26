package com.firstfood.identity;

import com.firstfood.identity.error.OtpDeliveryException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends email OTPs through Brevo's transactional email API (https://developers.brevo.com/reference/sendtransacemail).
 * Same bounded timeouts and the same "never log the request" rule as {@link BrevoSmsOtpSender}.
 *
 * Needs a verified sender address on the Brevo account: {@code BREVO_EMAIL_SENDER} (required in production).
 * Like the SMS sender, written against the published contract and not yet exercised against a live account.
 */
@Component
@Profile("production")
public class BrevoEmailOtpSender implements EmailOtpSender {

    private static final String SEND_EMAIL_URL = "https://api.brevo.com/v3/smtp/email";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;
    private final MeterRegistry meterRegistry;

    public BrevoEmailOtpSender(
            RestClient.Builder restClientBuilder,
            @Value("${app.notifications.brevo.api-key}") String apiKey,
            @Value("${app.notifications.brevo.email-sender}") String senderEmail,
            @Value("${app.notifications.brevo.sender-name}") String senderName,
            MeterRegistry meterRegistry) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.apiKey = apiKey;
        this.senderEmail = senderEmail;
        this.senderName = senderName;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void send(String email, String otpCode) {
        try {
            restClient.post()
                    .uri(SEND_EMAIL_URL)
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(new BrevoEmailRequest(
                            new Party(senderName, senderEmail),
                            List.of(new Party(null, email)),
                            "Your FirstFood verification code",
                            "<p>Your FirstFood verification code is <strong>" + otpCode
                                    + "</strong>.</p><p>It expires shortly. If you did not ask for it, ignore this email.</p>"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            meterRegistry.counter("firstfood.otp.delivery.failures", "provider", "brevo-email").increment();
            throw new OtpDeliveryException();
        }
    }

    private record Party(String name, String email) {
    }

    private record BrevoEmailRequest(Party sender, List<Party> to, String subject, String htmlContent) {
    }
}
