package com.delivery.harness.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEtaEstimatorTest {

    @Test
    void computesDeterministicClearAndStormEtas() {
        long clear = RoadEtaEstimator.estimateMinutes(39.9310, 116.4220, 39.9405, 116.4390, "晴");
        long storm = RoadEtaEstimator.estimateMinutes(39.9310, 116.4220, 39.9405, 116.4390, "暴雨");

        assertAll(
                () -> assertEquals(13L, clear),
                () -> assertEquals(19L, storm),
                () -> assertTrue(storm > clear),
                () -> assertEquals(1.0, RoadEtaEstimator.weatherFactor("晴")),
                () -> assertEquals(1.6, RoadEtaEstimator.weatherFactor("暴雨")),
                () -> assertEquals(1.3, RoadEtaEstimator.weatherFactor("小雨")),
                () -> assertEquals(1.15, RoadEtaEstimator.weatherFactor("大风")),
                () -> assertEquals(1.0, RoadEtaEstimator.weatherFactor(null)));
    }
}
