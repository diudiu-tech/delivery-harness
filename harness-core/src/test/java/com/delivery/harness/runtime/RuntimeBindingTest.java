package com.delivery.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.exception.RuntimeBoundaryException;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RuntimeBindingTest {
    @Test
    void snapshotsProtectBothInputAndAccessorCopiesFromMutation() {
        OrderInfo order = OrderInfo.builder().orderId("ORDER-1").build();
        RuleInfo rule = RuleInfo.builder().ruleId("RULE-1").content("Published policy").build();
        Map<String, Object> knowledge = new LinkedHashMap<>(Map.of("title", "Published document"));
        RuntimeBinding.Snapshot snapshot =
                new RuntimeBinding.Snapshot(
                        "config",
                        "hash",
                        "model",
                        "task",
                        0,
                        100,
                        2,
                        0,
                        false,
                        List.of(rule),
                        List.of(knowledge),
                        order);
        order.setOrderId("MUTATED");
        rule.setRuleId("MUTATED");
        knowledge.put("title", "MUTATED");
        try (RuntimeBinding ignored = RuntimeBinding.open(snapshot)) {
            RuntimeBinding.requireWorkflowContext();
            snapshot.order().setOrderId("ALSO-MUTATED");
            snapshot.rules().get(0).setRuleId("ALSO-MUTATED");
            snapshot.knowledge().get(0).put("title", "ALSO-MUTATED");
            assertEquals("ORDER-1", RuntimeBinding.evidenceOrder().getOrderId());
            assertEquals("RULE-1", RuntimeBinding.policyRules().get(0).getRuleId());
            assertEquals(
                    "Published document", RuntimeBinding.current().knowledge().get(0).get("title"));
        }
        assertNull(RuntimeBinding.current());
    }

    @Test
    void nestedContextsRestoreAuthorizationAndUsageWithoutWorkerLeakage() {
        try (RuntimeBinding outer =
                RuntimeBinding.bind(null, null, Map.of("model", "outer"), true)) {
            RuntimeBinding.recordCall(Map.of("provider_model", "outer-provider"));
            try (RuntimeBinding inner =
                    RuntimeBinding.bind(null, null, Map.of("model", "inner"), false)) {
                assertFalse(RuntimeBinding.damageEvidenceVerified());
                assertEquals("inner", RuntimeBinding.current().model());
            }
            assertTrue(RuntimeBinding.damageEvidenceVerified());
            assertEquals("outer-provider", RuntimeBinding.callMetadata().get("provider_model"));
        }
        assertNull(RuntimeBinding.current());
        assertFalse(RuntimeBinding.damageEvidenceVerified());
        assertEquals("UNKNOWN", RuntimeBinding.callMetadata().get("cost_status"));
    }

    @Test
    void privacyFailureDoesNotInstallAPartialRuntimeContext() {
        RuntimeBoundaryException failure =
                assertThrows(
                        RuntimeBoundaryException.class,
                        () ->
                                RuntimeBinding.bind(
                                        null, null, Map.of("prompt", "联系 13812345678"), false));
        assertEquals("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", failure.getReasonCode());
        assertFalse(failure.getMessage().contains("13812345678"));
        assertNull(RuntimeBinding.current());
    }

    @Test
    void canonicalRuntimeHashPreservesNumericMeaningAndIgnoresKeyOrder() {
        assertEquals(
                RuntimeData.hash(Map.of("amount", 5)), RuntimeData.hash(Map.of("amount", 5.00)));
        assertEquals(
                RuntimeData.hash(Map.of("a", 1, "b", 2)), RuntimeData.hash(Map.of("b", 2, "a", 1)));
    }

    @Test
    void executionDeadlineCanOnlyShortenARequestAndDoesNotLeakAcrossBindings() {
        long outerDeadline = System.nanoTime() + 2_000_000_000L;
        try (RuntimeBinding outer = RuntimeBinding.bind(null, null, Map.of(), false)) {
            RuntimeBinding.limitDeadline(outerDeadline);
            assertEquals(
                    outerDeadline, RuntimeBinding.requestDeadline(outerDeadline + 1_000_000_000L));
            try (RuntimeBinding inner = RuntimeBinding.bind(null, null, Map.of(), false)) {
                RuntimeBinding.limitDeadline(outerDeadline - 1_000_000_000L);
                assertEquals(
                        outerDeadline - 1_000_000_000L,
                        RuntimeBinding.requestDeadline(outerDeadline));
            }
            assertEquals(
                    outerDeadline, RuntimeBinding.requestDeadline(outerDeadline + 1_000_000_000L));
        }
        assertEquals(
                outerDeadline + 1_000_000_000L,
                RuntimeBinding.requestDeadline(outerDeadline + 1_000_000_000L));
    }
}
