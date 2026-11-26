package com.firstfood.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscoveryDistanceTest {

    @Test
    void samePointIsZero() {
        assertThat(ProviderDiscoveryServiceImpl.distanceKm(23.2599, 77.4126, 23.2599, 77.4126)).isZero();
    }

    @Test
    void bhopalToIndoreIsAboutAHundredAndSeventyKilometres() {
        double km = ProviderDiscoveryServiceImpl.distanceKm(23.2599, 77.4126, 22.7196, 75.8577);
        assertThat(km).isBetween(165.0, 180.0);
    }

    @Test
    void distanceIsSymmetric() {
        double ab = ProviderDiscoveryServiceImpl.distanceKm(12.9716, 77.5946, 13.0827, 80.2707);
        double ba = ProviderDiscoveryServiceImpl.distanceKm(13.0827, 80.2707, 12.9716, 77.5946);
        assertThat(ab).isEqualTo(ba);
    }
}
