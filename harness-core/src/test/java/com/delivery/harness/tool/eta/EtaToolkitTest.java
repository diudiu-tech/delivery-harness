package com.delivery.harness.tool.eta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ETA is the counterfactual an attribution is measured against — "the road
 * leg should have taken this long". A constant cannot serve that purpose, so
 * these tests check it actually varies with the route and the weather.
 */
class EtaToolkitTest {

    @Test
    void distanceGrowsWithSeparation() {
        double near = EtaToolkit.haversineMeters(39.9087, 116.3975, 39.9130, 116.4020);
        double far = EtaToolkit.haversineMeters(39.9087, 116.3975, 39.9405, 116.4390);

        assertTrue(near > 0, "two distinct points are not zero metres apart");
        assertTrue(far > near, "the further pair must measure further");
    }

    @Test
    void identicalPointsAreZeroApart() {
        assertEquals(0d, EtaToolkit.haversineMeters(39.9087, 116.3975, 39.9087, 116.3975), 1e-6);
    }

    @Test
    void oneDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        double meters = EtaToolkit.haversineMeters(39.0, 116.0, 40.0, 116.0);

        assertTrue(meters > 110_000 && meters < 112_000,
                "expected roughly 111 km, got " + meters);
    }

    @Test
    void badWeatherSlowsTheEstimate() {
        assertEquals(1.0d, EtaToolkit.weatherFactor("晴"));
        assertEquals(1.3d, EtaToolkit.weatherFactor("小雨"));
        assertEquals(1.6d, EtaToolkit.weatherFactor("暴雨"));
        assertEquals(1.15d, EtaToolkit.weatherFactor("大风"));
    }

    @Test
    void unknownOrMissingWeatherDoesNotPenaliseTheEstimate() {
        assertEquals(1.0d, EtaToolkit.weatherFactor(null));
        assertEquals(1.0d, EtaToolkit.weatherFactor("未知"));
    }
}
