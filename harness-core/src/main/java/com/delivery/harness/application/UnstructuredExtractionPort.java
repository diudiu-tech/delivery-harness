package com.delivery.harness.application;

import com.delivery.harness.evidence.EvidenceSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Optional hook for extracting unconfirmed signals from unstructured
 * conversation evidence inside a snapshot.
 *
 * <p>By architecture (ADR-0005), extracted signals are never passed to
 * {@code SingleSourcePolicyEngine}; they can only trigger human review routing
 * and surface conflict hints.
 */
public interface UnstructuredExtractionPort {

    ExtractionOutcome extract(EvidenceSnapshot snapshot);

    record ExtractionOutcome(
            boolean degraded,
            String degradedReason,
            List<Map<String, Object>> unconfirmedSignals,
            boolean untrustedTextDetected,
            boolean verificationFailed) {

        public static ExtractionOutcome empty() {
            return new ExtractionOutcome(false, null, List.of(), false, false);
        }
    }
}
