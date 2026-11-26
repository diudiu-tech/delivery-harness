package com.delivery.harness.diagnosis;

import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.evidence.SyntheticEvidenceSource;
import com.delivery.harness.policy.PolicyBundleLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies deterministic residual attribution against the PR2-locked
 * PolicyBundle thresholds and unmodified fixture numbers (ADR-0013).
 */
class DeterministicResidualEngineTest {

    private final PolicyBundleLoader policyLoader = PolicyBundleLoader.loadDefault();
    private final SyntheticEvidenceSource evidenceSource = new SyntheticEvidenceSource(policyLoader);
    private final DeterministicResidualEngine engine = new DeterministicResidualEngine(policyLoader);

    @Test
    void attributesMerchantSlowAndSevereMerchantSlowHonestly() {
        ResidualDiagnosis moderate = diagnose("ORD-MERCHANT-01");
        ResidualDiagnosis severe = diagnose("ORD-SEVERE-01");

        assertAll(
                () -> assertEquals(DiagnosisOutcome.SINGLE_PRIMARY_CANDIDATE, moderate.outcome()),
                () -> assertEquals(CausalFactorCode.MERCHANT_PREP_EXCESS, moderate.primaryCandidate()),
                () -> assertEquals(Optional.of(-1L), factor(moderate, CausalFactorCode.DISPATCH_WAIT_EXCESS)
                        .rawDeltaMinutes().knownValue(), "negative raw deltas must be preserved honestly"),
                () -> assertEquals(18L, factor(moderate, CausalFactorCode.MERCHANT_PREP_EXCESS).excessMinutes()),
                () -> assertEquals(12L, factor(moderate, CausalFactorCode.UNEXPLAINED_ROAD_EXCESS).excessMinutes()),
                () -> assertEquals(DiagnosisOutcome.SINGLE_PRIMARY_CANDIDATE, severe.outcome()),
                () -> assertEquals(CausalFactorCode.MERCHANT_PREP_EXCESS, severe.primaryCandidate()),
                () -> assertEquals(35L, factor(severe, CausalFactorCode.MERCHANT_PREP_EXCESS).excessMinutes()));
    }

    @Test
    void reportsMultiFactorWhenDispatchAndRoadExcessesAreWithinDominanceMargin() {
        // ORD-CAPACITY-01: dispatch excess = 22 - 4 = +18 (station strained 0.95),
        // road excess = 22 - 8 = +14. Lead is 4 < dominanceMargin (5) -> MULTI_FACTOR.
        ResidualDiagnosis diagnosis = diagnose("ORD-CAPACITY-01");

        assertAll(
                () -> assertEquals(DiagnosisOutcome.MULTI_FACTOR, diagnosis.outcome()),
                () -> assertNull(diagnosis.primaryCandidate()),
                () -> assertEquals(List.of(
                        CausalFactorCode.DISPATCH_WAIT_EXCESS,
                        CausalFactorCode.UNEXPLAINED_ROAD_EXCESS), diagnosis.significantFactors()),
                () -> assertTrue(factor(diagnosis, CausalFactorCode.DISPATCH_WAIT_EXCESS)
                        .supportingEvidenceIds().contains("EV-CAP-RATIO")),
                () -> assertEquals(Optional.of(-4L), factor(diagnosis, CausalFactorCode.MERCHANT_PREP_EXCESS)
                        .rawDeltaMinutes().knownValue()));
    }

    @Test
    void acceptsUnmodifiedWeatherFixtureTruthWithoutGamingNumbers() {
        // ORD-WEATHER-01: weather explains +6 min (19 - 13), while unexplained
        // road excess is +25 min (44 - 19). Engine must report the truth rather
        // than fudging numbers to force a weather label.
        ResidualDiagnosis diagnosis = diagnose("ORD-WEATHER-01");

        assertAll(
                () -> assertEquals(DiagnosisOutcome.SINGLE_PRIMARY_CANDIDATE, diagnosis.outcome()),
                () -> assertEquals(CausalFactorCode.UNEXPLAINED_ROAD_EXCESS, diagnosis.primaryCandidate()),
                () -> assertEquals(6L, factor(diagnosis, CausalFactorCode.WEATHER_EXPLAINED).excessMinutes()),
                () -> assertEquals(25L, factor(diagnosis, CausalFactorCode.UNEXPLAINED_ROAD_EXCESS).excessMinutes()),
                () -> assertTrue(diagnosis.evidenceGaps().stream().anyMatch(g -> g.contains("GPS"))));
    }

    @Test
    void usesNeutralNamingAndExposesGpsGapOnRoadExcessFixture() {
        ResidualDiagnosis diagnosis = diagnose("ORD-ROAD-01");

        assertAll(
                () -> assertEquals(DiagnosisOutcome.SINGLE_PRIMARY_CANDIDATE, diagnosis.outcome()),
                () -> assertEquals(CausalFactorCode.UNEXPLAINED_ROAD_EXCESS, diagnosis.primaryCandidate()),
                () -> assertEquals(32L, factor(diagnosis, CausalFactorCode.UNEXPLAINED_ROAD_EXCESS).excessMinutes()),
                () -> assertTrue(diagnosis.evidenceGaps().stream().anyMatch(g -> g.contains("不可自动定责骑手"))));
    }

    @Test
    void reportsNoOvertimeAnomalyForOnTimeOrder() {
        ResidualDiagnosis diagnosis = diagnose("ORD-ONTIME-01");

        assertAll(
                () -> assertEquals(DiagnosisOutcome.NO_OVERTIME_ANOMALY, diagnosis.outcome()),
                () -> assertNull(diagnosis.primaryCandidate()),
                () -> assertTrue(diagnosis.significantFactors().isEmpty()));
    }

    @Test
    void reportsUndeterminedOnMissingAndCorruptedTimestamps() {
        ResidualDiagnosis missing = diagnose("ORD-MISSING-TS-01");
        ResidualDiagnosis corrupt = diagnose("ORD-CORRUPT-TS-01");

        assertAll(
                () -> assertEquals(DiagnosisOutcome.UNDETERMINED, missing.outcome()),
                () -> assertNull(missing.primaryCandidate()),
                () -> assertTrue(missing.overtimeMinutes().isUnknown()),
                () -> assertEquals(DiagnosisOutcome.UNDETERMINED, corrupt.outcome()),
                () -> assertNull(corrupt.primaryCandidate()),
                () -> assertTrue(corrupt.overtimeMinutes().isInvalid()));
    }

    @Test
    void everyCitedEvidenceIdExistsInTheClosedSnapshot() {
        for (var fixture : evidenceSource.listAvailableFixtures()) {
            EvidenceSnapshot snapshot = evidenceSource.collect(fixture.orderId()).orElseThrow();
            ResidualDiagnosis diagnosis = engine.diagnose(snapshot);
            for (FactorEvaluation factor : diagnosis.factors()) {
                for (String id : factor.supportingEvidenceIds()) {
                    assertTrue(snapshot.containsEvidenceId(id),
                            fixture.orderId() + " cited unknown supporting ID: " + id);
                }
                for (String id : factor.contradictingEvidenceIds()) {
                    assertTrue(snapshot.containsEvidenceId(id),
                            fixture.orderId() + " cited unknown contradicting ID: " + id);
                }
            }
        }
    }

    private ResidualDiagnosis diagnose(String orderId) {
        EvidenceSnapshot snapshot = evidenceSource.collect(orderId).orElseThrow();
        return engine.diagnose(snapshot);
    }

    private static FactorEvaluation factor(ResidualDiagnosis diagnosis, CausalFactorCode code) {
        return diagnosis.factors().stream()
                .filter(f -> f.code() == code)
                .findFirst()
                .orElseThrow();
    }
}
