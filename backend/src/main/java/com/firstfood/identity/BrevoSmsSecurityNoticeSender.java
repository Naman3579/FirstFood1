package com.firstfood.identity;

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
 * Security notices over Brevo's Transactional SMS API, same account and sender as {@link BrevoSmsOtpSender}. Like that
 * class it is written against the published contract and not yet exercised against a live account. In India the
 * message text may need to match a DLT-registered template: register the notice wording before launch.
 *
 * <p>Failures are counted and swallowed by the caller ({@link SecurityNoticeServiceImpl}): a notice must never undo
 * the change it reports.
 */
@Component
@Profile("production")
public class BrevoSmsSecurityNoticeSender implements SecurityNoticeSender {

    private static final String SEND_SMS_URL = "https://api.brevo.com/v3/transactionalSMS/send";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String apiKey;
    private final String senderName;
    private final MeterRegistry meterRegistry;

    public BrevoSmsSecurityNoticeSender(
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
    public void send(String phone, String message) {
        try {
            restClient.post()
                    .uri(SEND_SMS_URL)
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(new BrevoSmsRequest(senderName, phone, message, "transactional"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            meterRegistry.counter("firstfood.notice.delivery.failures", "provider", "brevo").increment();
            throw new IllegalStateException("Security notice delivery failed");
        }
    }

    private record BrevoSmsRequest(String sender, String recipient, String content, String type) {
    }
}
