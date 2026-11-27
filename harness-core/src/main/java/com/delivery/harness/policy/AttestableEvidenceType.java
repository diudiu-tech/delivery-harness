package com.delivery.harness.policy;

import com.delivery.harness.common.evidence.EvidenceType;

/**
 * Closed set of evidence types a human reviewer may attest to generate an
 * {@code EV-HUMAN-*} record. Restricted strictly to types consumed by
 * {@link PolicyBundle} {@code requiredEvidence} so attestation never creates a
 * dead-end loop.
 */
public enum AttestableEvidenceType {
    DAMAGE_LEVEL(EvidenceType.DAMAGE_LEVEL);

    private final EvidenceType evidenceType;

    AttestableEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public EvidenceType evidenceType() {
        return evidenceType;
    }
}
