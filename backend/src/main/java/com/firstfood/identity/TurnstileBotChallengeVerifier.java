package com.firstfood.identity;

import com.firstfood.identity.error.BotChallengeFailedException;
import com.firstfood.identity.error.BotChallengeUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verifies a Cloudflare Turnstile token server-side (https://developers.cloudflare.com/turnstile/get-started/server-side-validation/).
 * A token is single-use and short-lived, so a script cannot replay one solved challenge for thousands of requests.
 *
 * <p>Fails CLOSED: if Cloudflare cannot be reached the OTP request is refused (503) rather than waved through, for the
 * same reason the rate limiter fails closed - a control that switches itself off during an outage is an invitation.
 * Not exercised against live Cloudflare from this repo's test environment: verify with real keys in staging, using
 * Cloudflare's published test keys first (docs/OPERATIONS.md).
 *
 * <p>Nothing here logs the token, the secret or the address.
 */
@Component
@ConditionalOnProperty(prefix = "app.bot-challenge", name = "enabled", havingValue = "true")
public class TurnstileBotChallengeVerifier implements BotChallengeVerifier {

    // Tokens are a few hundred characters; anything far longer is junk and is not forwarded to Cloudflare.
    private static final int MAX_TOKEN_LENGTH = 2048;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final BotChallengeProperties properties;
    private final MeterRegistry meterRegistry;

    // Builds its own RestClient rather than injecting RestClient.Builder: this bean must start whatever HTTP-client
    // auto-configuration is on the classpath, because a missing bean here would fail startup when the challenge is on.
    // @Autowired is required: with two constructors and none marked, Spring cannot choose and the context fails to start.
    @Autowired
    public TurnstileBotChallengeVerifier(BotChallengeProperties properties, MeterRegistry meterRegistry) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** For tests: supply a ready-made client (e.g. bound to a MockRestServiceServer). */
    TurnstileBotChallengeVerifier(RestClient restClient, BotChallengeProperties properties, MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void verify(String token, String clientIp) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            meterRegistry.counter("firstfood.botchallenge.results", "outcome", "missing").increment();
            throw new BotChallengeFailedException();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.secretKey());
        form.add("response", token.trim());
        if (clientIp != null && !clientIp.isBlank()) {
            form.add("remoteip", clientIp);
        }

        SiteVerifyResponse result;
        try {
            result = restClient.post()
                    .uri(properties.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(SiteVerifyResponse.class);
        } catch (RestClientException e) {
            meterRegistry.counter("firstfood.botchallenge.results", "outcome", "unavailable").increment();
            throw new BotChallengeUnavailableException();
        }

        if (result == null || !Boolean.TRUE.equals(result.success())) {
            meterRegistry.counter("firstfood.botchallenge.results", "outcome", "rejected").increment();
            throw new BotChallengeFailedException();
        }
        meterRegistry.counter("firstfood.botchallenge.results", "outcome", "passed").increment();
    }

    /** Only {@code success} is read; Cloudflare also returns error-codes, hostname, challenge_ts and so on. */
    private record SiteVerifyResponse(Boolean success) {
    }
}
