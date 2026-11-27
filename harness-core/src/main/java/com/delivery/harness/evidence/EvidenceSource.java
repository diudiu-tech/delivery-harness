package com.delivery.harness.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core SPI (1 of 3 per ADR-0004/Rev 2.1) for collecting immutable evidence
 * snapshots for an order at a point in time.
 *
 * <p>Implementations must distinguish unknown orders ({@link Optional#empty()})
 * from synthetic or external records, and must query station/telemetry evidence
 * against the order's event timestamp ({@code asOf}).
 */
public interface EvidenceSource {

    Optional<EvidenceSnapshot> collect(String orderId, Instant asOfOverride);

    default Optional<EvidenceSnapshot> collect(String orderId) {
        return collect(orderId, null);
    }

    List<FixtureSummary> listAvailableFixtures();

    String sourceMode();

    record FixtureSummary(
            String orderId,
            String category,
            String title,
            String defaultComplaintType) {}
}
