package com.delivery.harness.diagnosis;

import com.delivery.harness.common.evidence.EvidenceValue;

import java.util.List;

/**
 * Residual evaluation for one candidate delay factor.
 *
 * <p>{@code rawDeltaMinutes} retains negative deltas honestly (for example a
 * leg that finished faster than its baseline), while {@code excessMinutes}
 * clamps to {@code max(0, rawDelta)} for ranking.
 */
public record FactorEvaluation(
        CausalFactorCode code,
        String label,
        EvidenceValue<Long> rawDeltaMinutes,
        long excessMinutes,
        boolean exceedsThreshold,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds,
        List<String> evidenceGaps) {}
