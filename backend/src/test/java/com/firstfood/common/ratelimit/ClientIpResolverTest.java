package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The client address is the key to every per-IP limit, so forging it must not work. */
class ClientIpResolverTest {

    private final ClientIpResolver trusting = new ClientIpResolver(List.of("10.0.0.0/8", "fd00::/8", "192.168.1.5"));

    @Test
    void withNoTrustedProxiesTheForwardedHeaderIsNeverRead() {
        var resolver = new ClientIpResolver(List.of());
        assertThat(resolver.resolve("203.0.113.9", List.of("1.2.3.4"))).isEqualTo("203.0.113.9");
    }

    @Test
    void anUntrustedPeerCannotSpeakForAnotherAddress() {
        assertThat(trusting.resolve("203.0.113.9", List.of("1.2.3.4"))).isEqualTo("203.0.113.9");
    }

    @Test
    void aTrustedPeerReportsTheClient() {
        assertThat(trusting.resolve("10.1.2.3", List.of("198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void entriesLeftOfTheFirstUntrustedHopAreAttackerControlledAndIgnored() {
        assertThat(trusting.resolve("10.1.2.3", List.of("6.6.6.6, 198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void trustedHopsInTheChainAreSkipped() {
        assertThat(trusting.resolve("10.1.2.3", List.of("198.51.100.7, 10.9.9.9, 192.168.1.5")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void multipleHeaderLinesAreTreatedAsOneList() {
        assertThat(trusting.resolve("10.1.2.3", List.of("6.6.6.6", "198.51.100.7, 10.0.0.9")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void hostnamesAndGarbageAreNeverResolvedOrTrusted() {
        assertThat(trusting.resolve("10.1.2.3", List.of("evil.example.com"))).isEqualTo("10.1.2.3");
        assertThat(trusting.resolve("10.1.2.3", List.of("localhost"))).isEqualTo("10.1.2.3");
        assertThat(trusting.resolve("10.1.2.3", List.of("999.1.1.1"))).isEqualTo("10.1.2.3");
    }

    @Test
    void portsAreStripped() {
        assertThat(trusting.resolve("10.1.2.3", List.of("198.51.100.7:5555"))).isEqualTo("198.51.100.7");
    }

    @Test
    void cidrBoundariesAreExact() {
        var resolver = new ClientIpResolver(List.of("172.16.0.0/12"));
        assertThat(resolver.resolve("172.31.255.255", List.of("1.1.1.1"))).isEqualTo("1.1.1.1");
        assertThat(resolver.resolve("172.32.0.1", List.of("1.1.1.1"))).isEqualTo("172.32.0.1");
    }

    @Test
    void unusablePeerAddressResolvesToUnknown() {
        assertThat(trusting.resolve("not-an-ip", List.of("1.1.1.1"))).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(trusting.resolve(null, null)).isEqualTo(ClientIpResolver.UNKNOWN);
    }

    @Test
    void ipv6ClientsAreLimitedByTheirSlash64() {
        assertThat(ClientIpResolver.bucket("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientIpResolver.bucket("2001:db8:1:2:1:1:1:1"))
                .isEqualTo(ClientIpResolver.bucket("2001:db8:1:2:ffff:0:0:9"));
        assertThat(ClientIpResolver.bucket("203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void invalidConfigurationFailsFastInsteadOfTrustingTheWrongThing() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("nonsense"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33"))).isInstanceOf(IllegalArgumentException.class);
    }

    /** F-01: the shipped prod topology. nginx (172.28.10.2) overwrites X-Forwarded-For with the real client. */
    @Test
    void shippedNginxTopologyGivesEachClientItsOwnAddress() {
        var resolver = new ClientIpResolver(List.of("172.28.10.2/32"));
        String viaNginxA = resolver.resolve("172.28.10.2", List.of("203.0.113.10"));
        String viaNginxB = resolver.resolve("172.28.10.2", List.of("198.51.100.20"));
        assertThat(viaNginxA).isEqualTo("203.0.113.10");
        assertThat(viaNginxB).isEqualTo("198.51.100.20");
        assertThat(viaNginxA).isNotEqualTo(viaNginxB);
    }

    @Test
    void withoutTheTrustedProxySettingEveryUserCollapsesIntoTheProxyAddress() {
        // The bug F-01 describes: documents why the production profile now refuses to start with it unset.
        var unset = new ClientIpResolver(List.of());
        assertThat(unset.resolve("172.28.10.2", List.of("203.0.113.10")))
                .isEqualTo(unset.resolve("172.28.10.2", List.of("198.51.100.20")))
                .isEqualTo("172.28.10.2");
    }

    @Test
    void onlyTheNginxAddressIsTrustedNotOtherContainersOnTheSameSubnet() {
        var resolver = new ClientIpResolver(List.of("172.28.10.2/32"));
        // e.g. a compromised frontend container (172.28.10.3) cannot choose its own rate-limit bucket.
        assertThat(resolver.resolve("172.28.10.3", List.of("203.0.113.10"))).isEqualTo("172.28.10.3");
    }
}
