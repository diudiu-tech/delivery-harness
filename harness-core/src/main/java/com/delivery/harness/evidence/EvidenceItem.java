package com.delivery.harness.evidence;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;

import java.util.Objects;

/**
 * One addressed entry inside a closed {@link EvidenceSnapshot} ledger.
 */
public record EvidenceItem(
        String evidenceId,
        EvidenceType type,
        EvidenceValue<?> value) {

    public EvidenceItem {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
