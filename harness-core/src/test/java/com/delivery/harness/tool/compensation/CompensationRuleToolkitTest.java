package com.delivery.harness.tool.compensation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.tool.gateway.ToolGateway;
import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class CompensationRuleToolkitTest {
    private ToolGateway gateway;
    private RuleBaseService rules;
    private CompensationRuleToolkit toolkit;

    @BeforeEach
    void init() throws Exception {
        gateway = new ToolGateway(100);
        rules = new RuleBaseService();
        try (var stream = getClass().getResourceAsStream("/seed/rules.json")) {
            List<RuleInfo> seed =
                    JsonUtil.fromJson(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                            new TypeReference<>() {});
            seed.forEach(rules::save);
        }
        toolkit = new CompensationRuleToolkit(gateway, rules);
        toolkit.init();
    }

    @Test
    void secondBoundariesFollowExplicitFloorPolicy() {
        long[] seconds = {0, 1, 59, 60, 899, 900, 901, 959, 960, 1799, 1800, 1801, 1859, 1860};
        String[] ids = {
            "COMP-006",
            "COMP-006",
            "COMP-006",
            "COMP-003",
            "COMP-003",
            "COMP-003",
            "COMP-003",
            "COMP-003",
            "COMP-002",
            "COMP-002",
            "COMP-002",
            "COMP-002",
            "COMP-002",
            "COMP-001"
        };
        for (int i = 0; i < seconds.length; i++) {
            Map<String, Object> result =
                    decide(
                            "OVERTIME",
                            seconds[i],
                            new BigDecimal("64.00"),
                            Map.of("refundable_balance", "64.00"));
            Map<?, ?> rule = (Map<?, ?>) ((List<?>) result.get("matched_rules")).get(0);
            assertEquals(ids[i], rule.get("rule_id"), "seconds=" + seconds[i]);
            assertEquals("DETERMINED", result.get("decision_status"));
            assertEquals("FLOOR", ((Map<?, ?>) result.get("calculation")).get("minute_rounding"));
        }
    }

    @Test
    void missingEvidenceAndUnsupportedPolicyNeverBecomeZeroAmountDecisions() {
        assertNull(
                decide("OVERTIME", null, new BigDecimal("64"), Map.of())
                        .get("authoritative_amount"));
        assertNull(
                decide("OVERTIME", -1L, new BigDecimal("64"), Map.of())
                        .get("authoritative_amount"));
        Map<String, Object> missing = decide("MISSING_ITEM", 0L, new BigDecimal("64"), Map.of());
        assertEquals("OUT_OF_SCOPE", missing.get("decision_status"));
        assertNull(missing.get("authoritative_amount"));
        for (Object raw : List.of("bad", "-1", "0.001")) {
            Map<String, Object> result = decide("OVERTIME", 1860L, raw, Map.of());
            assertEquals("INSUFFICIENT_EVIDENCE", result.get("decision_status"));
            assertNull(result.get("authoritative_amount"));
        }
    }

    @Test
    void unverifiedDamagePreservesOnlyV1Candidate() {
        Map<String, Object> result =
                decide("DAMAGED", 0L, new BigDecimal("64"), Map.of("damage_level", "FULL"));
        assertEquals(new BigDecimal("64.00"), result.get("suggested_amount"));
        assertNull(result.get("authoritative_amount"));
        assertEquals("INSUFFICIENT_EVIDENCE", result.get("decision_status"));
        Map<String, Object> verified =
                decide(
                        "DAMAGED",
                        0L,
                        new BigDecimal("64"),
                        Map.of(
                                "damage_level",
                                "PARTIAL",
                                "damage_evidence_verified",
                                true,
                                "refundable_balance",
                                "64.00"));
        assertEquals(new BigDecimal("20.00"), verified.get("authoritative_amount"));
        assertEquals("DETERMINED", verified.get("decision_status"));
    }

    @Test
    void equalPriorityOverlapIsAConflictAndNotAnArbitraryFirstMatch() {
        RuleInfo duplicate = rules.findById("COMP-002").orElseThrow();
        duplicate.setRuleId("COMP-999");
        rules.save(duplicate);
        Map<String, Object> result = decide("OVERTIME", 960L, new BigDecimal("64"), Map.of());
        assertEquals("POLICY_CONFLICT", result.get("decision_status"));
        assertNull(result.get("authoritative_amount"));
        assertEquals(2, ((List<?>) result.get("matched_rules")).size());
    }

    @Test
    void canonicalAndLegacyRefundMethodsEnforceTheSameCashBalance() {
        for (String method : List.of("REFUND", "原路退款")) {
            RuleInfo refund = rules.findById("COMP-001").orElseThrow();
            refund.setVersion(method);
            refund.getActions().put("method", method);
            rules.save(refund);
            Map<String, Object> result =
                    decide(
                            "OVERTIME",
                            1860L,
                            new BigDecimal("64.00"),
                            Map.of("refundable_balance", "10.00"));
            assertEquals("POLICY_CONFLICT", result.get("decision_status"), method);
            assertNull(result.get("authoritative_amount"), method);
            assertEquals("MANUAL", result.get("method"));
        }
    }

    @Test
    void cashRefundNeverInfersBalanceFromOriginalOrderAmount() {
        Map<String, Object> missing = decide("OVERTIME", 1860L, new BigDecimal("64.00"), Map.of());
        assertEquals("INSUFFICIENT_EVIDENCE", missing.get("decision_status"));
        assertNull(missing.get("authoritative_amount"));
        assertNull(missing.get("refundable_balance"));
        assertTrue(
                ((List<?>) missing.get("decision_reasons")).contains("refundable_balance_missing"));
        for (String invalid : List.of("not-a-number", "-1", "0.001", "64.01")) {
            Map<String, Object> rejected =
                    decide(
                            "OVERTIME",
                            1860L,
                            new BigDecimal("64.00"),
                            Map.of("refundable_balance", invalid));
            assertEquals("POLICY_CONFLICT", rejected.get("decision_status"), invalid);
            assertNull(rejected.get("authoritative_amount"), invalid);
        }
        Map<String, Object> empty =
                decide(
                        "OVERTIME",
                        1860L,
                        new BigDecimal("64.00"),
                        Map.of("refundable_balance", "0.00"));
        assertEquals("POLICY_CONFLICT", empty.get("decision_status"));
        assertNull(empty.get("authoritative_amount"));
        Map<String, Object> verified =
                decide(
                        "OVERTIME",
                        1860L,
                        new BigDecimal("64.00"),
                        Map.of("refundable_balance", "64.00"));
        assertEquals(new BigDecimal("20.00"), verified.get("authoritative_amount"));
        assertEquals("REFUND", verified.get("method"));
    }

    @Test
    void approvalThresholdIsSharedAndCanonicalMethodIsReturned() {
        ReflectionTestUtils.setField(toolkit, "approvalThreshold", new BigDecimal("5.00"));
        Map<String, Object> result = decide("OVERTIME", 960L, new BigDecimal("64.00"), Map.of());
        assertEquals("COUPON", result.get("method"));
        assertEquals("COUPON", result.get("suggested_method"));
        assertEquals(true, result.get("approval_required"));
        assertTrue(
                ((List<?>) result.get("decision_reasons")).contains("approval_threshold_reached"));
    }

    private Map<String, Object> decide(
            String type, Long seconds, Object amount, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>(extra);
        p.put("complaint_type", type);
        p.put("overtime_seconds", seconds);
        p.put("order_amount", amount);
        return (Map<String, Object>) gateway.invoke("compensation_rule", p).getData();
    }
}
