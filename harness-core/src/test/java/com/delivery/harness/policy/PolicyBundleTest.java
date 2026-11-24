package com.delivery.harness.policy;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.common.evidence.MinuteRoundingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyBundleTest {

    private static final Instant EVAL_AT = Instant.parse("2026-09-01T04:00:00Z");

    private final PolicyBundleLoader loader = PolicyBundleLoader.loadDefault();
    private final SingleSourcePolicyEngine engine = new SingleSourcePolicyEngine(loader);

    @Test
    void loadsAndValidatesDefaultBundleWithAutoRenderedDescriptions() {
        PolicyBundle bundle = loader.activeBundle();
        PolicyBundle.CompensationRule moderate = bundle.compensationRules().stream()
                .filter(r -> "COMP-002".equals(r.ruleId()))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals("policy-v1.0.0", bundle.policyVersion()),
                () -> assertEquals(64, loader.activePolicyHash().length()),
                () -> assertEquals(MinuteRoundingMode.FLOOR_TRUNCATE, bundle.minuteRoundingMode()),
                () -> assertEquals(new BigDecimal("10.00"), bundle.limits().approvalThreshold()),
                () -> assertEquals(new BigDecimal("50.00"), bundle.limits().escalationCap()),
                () -> assertTrue(moderate.renderDescription().contains("16 至 30 分钟")),
                () -> assertTrue(moderate.renderDescription().contains("5.00 元")));
    }

    @Test
    void rejectsOverlappingOvertimeIntervalsAtStartup() {
        PolicyBundle base = loader.activeBundle();
        List<PolicyBundle.CompensationRule> overlapping = List.of(
                overtimeRule("R1", 0, 15),
                overtimeRule("R2", 15, null));
        PolicyBundle bad = copyWithRules(base, overlapping);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PolicyBundleValidator.validate(bad));
        assertTrue(ex.getMessage().contains("overlap"));
    }

    @Test
    void rejectsOvertimeIntervalGapAtStartup() {
        PolicyBundle base = loader.activeBundle();
        List<PolicyBundle.CompensationRule> gapped = List.of(
                overtimeRule("R1", 0, 14),
                overtimeRule("R2", 16, null));
        PolicyBundle bad = copyWithRules(base, gapped);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PolicyBundleValidator.validate(bad));
        assertTrue(ex.getMessage().contains("gap"));
    }

    @Test
    void rejectsBoundedTopTierAndMissingZeroStart() {
        PolicyBundle base = loader.activeBundle();
        PolicyBundle missingZero = copyWithRules(base, List.of(overtimeRule("R1", 1, null)));
        PolicyBundle boundedTop = copyWithRules(base, List.of(overtimeRule("R1", 0, 30)));

        assertTrue(PolicyBundleValidator.collectErrors(missingZero).stream()
                .anyMatch(e -> e.contains("start at 0")));
        assertTrue(PolicyBundleValidator.collectErrors(boundedTop).stream()
                .anyMatch(e -> e.contains("unbounded")));
    }

    @Test
    void rejectsIncompleteRoutingMatrixAndInvalidLimits() {
        PolicyBundle base = loader.activeBundle();
        Map<AdjudicationStatus, RoutingAction> incomplete = new EnumMap<>(AdjudicationStatus.class);
        incomplete.put(AdjudicationStatus.ELIGIBLE, RoutingAction.PENDING_DECISION);
        PolicyBundle badRouting = new PolicyBundle(
                base.policyVersion(), base.effectiveFrom(), base.effectiveTo(),
                base.evaluationClockField(), base.minuteRoundingMode(), base.stalenessPolicySeconds(),
                base.attributionThresholds(),
                new PolicyBundle.Limits(new BigDecimal("60.00"), new BigDecimal("50.00")),
                base.compensationRules(),
                new PolicyBundle.RoutingMatrix(
                        incomplete, RoutingAction.ESCALATED, RoutingAction.PENDING_DECISION,
                        RoutingAction.ESCALATED, RoutingAction.ESCALATED));

        List<String> errors = PolicyBundleValidator.collectErrors(badRouting);
        assertTrue(errors.stream().anyMatch(e -> e.contains("approvalThreshold must be <= limits.escalationCap")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("routingMatrix missing mapping")));

        // Duplicate undistinguished non-OVERTIME rules must also be rejected.
        List<PolicyBundle.CompensationRule> dupWrongOrder = List.of(
                overtimeRule("OT-0", 0, null),
                new PolicyBundle.CompensationRule("WO-1", "错单1", ComplaintType.WRONG_ORDER, 90,
                        List.of(EvidenceType.ORDER_STATUS), null, null, PolicyBundle.AmountType.FIXED,
                        new BigDecimal("5.00"), null, null, null, null, "优惠券", false),
                new PolicyBundle.CompensationRule("WO-2", "错单2", ComplaintType.WRONG_ORDER, 80,
                        List.of(EvidenceType.ORDER_STATUS), null, null, PolicyBundle.AmountType.FIXED,
                        new BigDecimal("3.00"), null, null, null, null, "优惠券", false));
        assertTrue(PolicyBundleValidator.collectErrors(copyWithRules(base, dupWrongOrder)).stream()
                .anyMatch(e -> e.contains("Non-OVERTIME complaintType WRONG_ORDER")));
    }

    @Test
    void separatesAllSixAdjudicationOutcomesCleanly() {
        // 1. ELIGIBLE
        SingleSourcePolicyEngine.AdjudicationResult eligible = engine.evaluate(
                completeOvertimeInput("DELIVERED", 18L, "35.50", EVAL_AT));
        assertAll(
                () -> assertEquals(AdjudicationStatus.ELIGIBLE, eligible.status()),
                () -> assertEquals("COMP-002", eligible.matchedRuleId()),
                () -> assertEquals(new BigDecimal("5.00"), eligible.actionableAmount()),
                () -> assertFalse(eligible.approvalRequired()));

        // 2. INELIGIBLE (affirmative 0.00 on-time)
        SingleSourcePolicyEngine.AdjudicationResult ineligible = engine.evaluate(
                completeOvertimeInput("DELIVERED", 0L, "31.00", EVAL_AT));
        assertAll(
                () -> assertEquals(AdjudicationStatus.INELIGIBLE, ineligible.status()),
                () -> assertEquals("COMP-006", ineligible.matchedRuleId()),
                () -> assertEquals(new BigDecimal("0.00"), ineligible.actionableAmount()),
                () -> assertFalse(ineligible.approvalRequired()));

        // 3. NEEDS_EVIDENCE (missing overtime)
        SingleSourcePolicyEngine.AdjudicationResult needsEvidence = engine.evaluate(
                new SingleSourcePolicyEngine.PolicyEvaluationInput(
                        EvidenceValue.known("DELIVERED", "test", EVAL_AT),
                        ComplaintType.OVERTIME,
                        EvidenceValue.unknown("MISSING_ACTUAL_TIME", "deliveryTime is null"),
                        EvidenceValue.known(new BigDecimal("35.50"), "test", EVAL_AT),
                        EvidenceValue.unknown("NOT_PROVIDED", "n/a"),
                        EVAL_AT,
                        Map.of()));
        assertAll(
                () -> assertEquals(AdjudicationStatus.NEEDS_EVIDENCE, needsEvidence.status()),
                () -> assertNull(needsEvidence.actionableAmount()),
                () -> assertTrue(needsEvidence.missingEvidence().contains("TIMELINE_OVERTIME_MINUTES")));

        // 4. EXCEEDS_CAP_ESCALATE (64.00 FULL damage > 50.00 escalation cap)
        SingleSourcePolicyEngine.AdjudicationResult overCap = engine.evaluate(
                new SingleSourcePolicyEngine.PolicyEvaluationInput(
                        EvidenceValue.known("DELIVERED", "test", EVAL_AT),
                        ComplaintType.DAMAGED,
                        EvidenceValue.known(0L, "test", EVAL_AT),
                        EvidenceValue.known(new BigDecimal("64.00"), "test", EVAL_AT),
                        EvidenceValue.known(DamageLevel.FULL, "EV-HUMAN-1", EVAL_AT),
                        EVAL_AT,
                        Map.of()));
        assertAll(
                () -> assertEquals(AdjudicationStatus.EXCEEDS_CAP_ESCALATE, overCap.status()),
                () -> assertNull(overCap.actionableAmount(), "over-cap amount must never be actionable"),
                () -> assertEquals(new BigDecimal("64.00"), overCap.calculatedAmount()),
                () -> assertTrue(overCap.approvalRequired()));

        // 5. NO_APPLICABLE_POLICY (MISSING_ITEM + outside effective window)
        SingleSourcePolicyEngine.AdjudicationResult noRule = engine.evaluate(
                new SingleSourcePolicyEngine.PolicyEvaluationInput(
                        EvidenceValue.known("DELIVERED", "test", EVAL_AT),
                        ComplaintType.MISSING_ITEM,
                        EvidenceValue.known(0L, "test", EVAL_AT),
                        EvidenceValue.known(new BigDecimal("35.00"), "test", EVAL_AT),
                        EvidenceValue.unknown("NOT_PROVIDED", "n/a"),
                        EVAL_AT,
                        Map.of()));
        SingleSourcePolicyEngine.AdjudicationResult outsideWindow = engine.evaluate(
                completeOvertimeInput("DELIVERED", 18L, "35.50", Instant.parse("2025-12-31T23:59:59Z")));
        assertAll(
                () -> assertEquals(AdjudicationStatus.NO_APPLICABLE_POLICY, noRule.status()),
                () -> assertNull(noRule.actionableAmount()),
                () -> assertEquals(AdjudicationStatus.NO_APPLICABLE_POLICY, outsideWindow.status()),
                () -> assertNull(outsideWindow.actionableAmount()));

        // 6. OUT_OF_SCOPE (non-DELIVERED order)
        SingleSourcePolicyEngine.AdjudicationResult outOfScope = engine.evaluate(
                completeOvertimeInput("IN_TRANSIT", 18L, "35.50", EVAL_AT));
        assertAll(
                () -> assertEquals(AdjudicationStatus.OUT_OF_SCOPE, outOfScope.status()),
                () -> assertNull(outOfScope.actionableAmount()));
    }

    @Test
    void detectsDynamicStalenessWithoutMutatingKnownEvidence() {
        EvidenceValue.Known<Double> fresh = EvidenceValue.known(
                0.90, "capacity", EVAL_AT.plusSeconds(300), EVAL_AT);
        EvidenceValue.Known<Double> stale = EvidenceValue.known(
                0.90, "capacity", EVAL_AT.plusSeconds(3600), EVAL_AT);

        assertFalse(SingleSourcePolicyEngine.isStale(
                loader.activeBundle(), EvidenceType.STATION_CAPACITY_RATIO, fresh));
        assertTrue(SingleSourcePolicyEngine.isStale(
                loader.activeBundle(), EvidenceType.STATION_CAPACITY_RATIO, stale));
    }

    @Test
    void routesDeterministicallyThroughValidatedMatrix() {
        SingleSourcePolicyEngine.AdjudicationResult eligible = engine.evaluate(
                completeOvertimeInput("DELIVERED", 18L, "35.50", EVAL_AT));
        SingleSourcePolicyEngine.AdjudicationResult severe = engine.evaluate(
                completeOvertimeInput("DELIVERED", 35L, "64.00", EVAL_AT));

        assertAll(
                () -> assertEquals(RoutingAction.PENDING_DECISION,
                        engine.route(eligible, false, false, false)),
                () -> assertEquals(RoutingAction.ESCALATED,
                        engine.route(eligible, false, true, false)),
                () -> assertEquals(RoutingAction.ESCALATED,
                        engine.route(eligible, false, false, true)),
                () -> assertEquals(RoutingAction.ESCALATED,
                        engine.route(severe, false, false, false)));
    }

    private static SingleSourcePolicyEngine.PolicyEvaluationInput completeOvertimeInput(
            String status, long overtimeMinutes, String amount, Instant evalAt) {
        Map<EvidenceType, EvidenceValue<?>> present = new EnumMap<>(EvidenceType.class);
        present.put(EvidenceType.ORDER_STATUS, EvidenceValue.known(status, "test", evalAt));
        present.put(EvidenceType.ORDER_AMOUNT, EvidenceValue.known(new BigDecimal(amount), "test", evalAt));
        present.put(EvidenceType.ORDER_CREATE_TIME, EvidenceValue.known(Boolean.TRUE, "test", evalAt));
        present.put(EvidenceType.ORDER_DELIVERY_TIME, EvidenceValue.known(Boolean.TRUE, "test", evalAt));
        present.put(EvidenceType.ORDER_EXPECTED_DELIVERY_TIME, EvidenceValue.known(Boolean.TRUE, "test", evalAt));
        present.put(EvidenceType.TIMELINE_OVERTIME_MINUTES, EvidenceValue.known(overtimeMinutes, "test", evalAt));
        return new SingleSourcePolicyEngine.PolicyEvaluationInput(
                EvidenceValue.known(status, "test", evalAt),
                ComplaintType.OVERTIME,
                EvidenceValue.known(overtimeMinutes, "test", evalAt),
                EvidenceValue.known(new BigDecimal(amount), "test", evalAt),
                EvidenceValue.unknown("NOT_PROVIDED", "n/a"),
                evalAt,
                present);
    }

    private static PolicyBundle.CompensationRule overtimeRule(String id, int min, Integer max) {
        return new PolicyBundle.CompensationRule(
                id, id, ComplaintType.OVERTIME, 50,
                List.of(EvidenceType.ORDER_STATUS),
                min, max, PolicyBundle.AmountType.FIXED,
                new BigDecimal("1.00"), null, null, null, null, "红包", false);
    }

    private static PolicyBundle copyWithRules(PolicyBundle base, List<PolicyBundle.CompensationRule> rules) {
        return new PolicyBundle(
                base.policyVersion(), base.effectiveFrom(), base.effectiveTo(),
                base.evaluationClockField(), base.minuteRoundingMode(), base.stalenessPolicySeconds(),
                base.attributionThresholds(), base.limits(), rules, base.routingMatrix());
    }
}
