package com.delivery.harness.diagnosis;

import com.delivery.harness.common.evidence.EvidenceValue;

import java.util.List;

/**
 * Deterministic residual diagnosis for an immutable {@code EvidenceSnapshot}.
 *
 * <p>Deliberately omits any automatic {@code liabilityAllocation}: structured
 * telemetry narrows candidate delay factors and exposes evidence gaps, while
 * responsibility decisions belong exclusively to human reviewers.
 */
public record ResidualDiagnosis(
        DiagnosisOutcome outcome,
        CausalFactorCode primaryCandidate,
        List<CausalFactorCode> significantFactors,
        List<FactorEvaluation> factors,
        EvidenceValue<Long> overtimeMinutes,
        long sumOfPositiveExcessMinutes,
        List<String> evidenceGaps,
        String summary) {}
