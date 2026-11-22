package com.firstfood.common.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    /** Fails startup on an invalid CIDR rather than silently trusting (or distrusting) the wrong proxies. */
    @Bean
    ClientIpResolver clientIpResolver(RateLimitProperties properties) {
        return new ClientIpResolver(properties.trustedProxies());
    }
}
