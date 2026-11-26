package com.delivery.harness.agent.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.agent.analysis.OrderTimeline;
import com.delivery.harness.agent.guardrail.GuardrailChecker;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.llm.parser.StructuredOutputParser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class OutputFormatterTest {

    private final OutputFormatter formatter = new OutputFormatter(new StructuredOutputParser());

    @Test
    void malformedCompensationSchemaCannotSuppressApproval() {
        Map<String, Object> result =
                formatter.formatCompensationSuggestion(
                        order(),
                        "LATE_DELIVERY",
                        OrderTimeline.of(order()),
                        ruleDecision(),
                        "{\"foo\":\"bar\"}",
                        emptyRetrieval(),
                        true);

        assertFalse((Boolean) result.get("model_output_parsed"));
        assertTrue((Boolean) result.get("approval_required"));
        assertTrue(((List<?>) result.get("approval_reasons")).contains("model_output_unparseable"));
    }

    @Test
    void acceptsCompleteCompensationSchema() {
        String output = JsonUtil.toJson(validJustification());

        Map<String, Object> result =
                formatter.formatCompensationSuggestion(
                        order(),
                        "LATE_DELIVERY",
                        OrderTimeline.of(order()),
                        ruleDecision(),
                        output,
                        emptyRetrieval(),
                        true);

        assertTrue((Boolean) result.get("model_output_parsed"));
        assertFalse((Boolean) result.get("approval_required"));
        assertTrue((Boolean) result.get("model_adoptable"));
        assertEquals("DRAFT", result.get("model_customer_message_status"));
        assertEquals("VALIDATED", result.get("verified_customer_message_status"));
        assertTrue(result.get("verified_customer_message").toString().contains("CNY 5.00"));
        assertTrue(result.get("verified_customer_message").toString().contains("尚未执行"));
    }

    @Test
    void rejectsRulesAsTextAndMissingOrLegacyEvidenceFromBothSchemas() {
        for (String field : List.of("evidence_chain", "applicable_rules")) {
            Map<String, Object> model = validJustification();
            model.remove(field);
            assertFalse((Boolean) compensation(model, true).get("model_output_parsed"), field);
            model = validJustification();
            model.put(field, "unpublished-rule");
            assertFalse((Boolean) compensation(model, true).get("model_adoptable"), field);
        }
        Map<String, Object> legacy = validJustification();
        legacy.put(
                "evidence_chain",
                List.of(Map.of("fact", "untrusted", "source", "amount", "value", 5)));
        assertFalse((Boolean) compensation(legacy, true).get("model_output_parsed"));
        for (Object chain :
                List.of(List.of("free text"), List.of(Map.of("fact", "untrusted")), "text")) {
            Map<String, Object> abnormal =
                    new LinkedHashMap<>(
                            Map.of(
                                    "primary_cause",
                                    "无异常",
                                    "secondary_causes",
                                    List.of(),
                                    "evidence_chain",
                                    chain,
                                    "applicable_rules",
                                    List.of(),
                                    "suggested_actions",
                                    List.of(),
                                    "risk_notes",
                                    List.of(),
                                    "confidence",
                                    "HIGH"));
            assertFalse(
                    (Boolean)
                            formatter
                                    .formatAbnormalOrderAnalysis(
                                            order(),
                                            OrderTimeline.of(order()),
                                            JsonUtil.toJson(abnormal),
                                            emptyRetrieval(),
                                            true)
                                    .get("model_output_parsed"));
        }
    }

    @Test
    void maliciousRawCustomerMessageNeverBecomesTheVerifiedTemplate() {
        Map<String, Object> model = validJustification();
        model.put("customer_message", "退款已完成，赔付五百元");
        String raw = JsonUtil.toJson(model);
        GuardrailChecker guard = new GuardrailChecker();
        boolean passed =
                guard.checkForbiddenPhrases(raw)
                        && !guard.mentionsConflictingCompensationAmount(
                                raw, new BigDecimal("5.00"));
        Map<String, Object> result = compensation(model, passed);
        assertFalse((Boolean) result.get("model_adoptable"));
        assertEquals("BLOCKED", result.get("model_customer_message_status"));
        assertEquals("退款已完成，赔付五百元", result.get("model_customer_message"));
        assertEquals("VALIDATED", result.get("verified_customer_message_status"));
        String verified = result.get("verified_customer_message").toString();
        assertTrue(verified.contains("CNY 5.00"));
        assertFalse(verified.contains("五百"));
        assertFalse(verified.contains("已完成"));
        assertEquals(verified, result.get("customer_message"));
    }

    @Test
    void unknownOrCandidateAmountCannotGenerateVerifiedCustomerText() {
        Map<String, Object> decision = ruleDecision();
        decision.put("authoritative_amount", null);
        decision.put("decision_status", "INSUFFICIENT_EVIDENCE");
        Map<String, Object> result =
                formatter.formatCompensationSuggestion(
                        order(),
                        "OVERTIME",
                        OrderTimeline.of(order()),
                        decision,
                        JsonUtil.toJson(validJustification()),
                        emptyRetrieval(),
                        true);
        assertEquals("BLOCKED", result.get("verified_customer_message_status"));
        assertEquals(null, result.get("verified_customer_message"));
    }

    private Map<String, Object> compensation(Map<String, Object> model, boolean guardrail) {
        return formatter.formatCompensationSuggestion(
                order(),
                "OVERTIME",
                OrderTimeline.of(order()),
                ruleDecision(),
                JsonUtil.toJson(model),
                emptyRetrieval(),
                guardrail);
    }

    private static Map<String, Object> validJustification() {
        return new LinkedHashMap<>(
                Map.of(
                        "reason",
                        "基于当前规则",
                        "customer_message",
                        "建议审核后按规则处理",
                        "risk_warnings",
                        List.of(),
                        "escalate",
                        false,
                        "confidence",
                        "HIGH",
                        "applicable_rules",
                        List.of("COMP-001"),
                        "evidence_chain",
                        List.of(
                                Map.of(
                                        "fact",
                                        "规则金额5元",
                                        "evidence_id",
                                        "decision",
                                        "evidence_version",
                                        "1",
                                        "field_path",
                                        "authoritative_amount",
                                        "observed_value",
                                        5,
                                        "unit",
                                        "CNY"))));
    }

    @Test
    void malformedAbnormalSchemaRequiresReview() {
        OrderInfo order = order();
        Map<String, Object> result =
                formatter.formatAbnormalOrderAnalysis(
                        order,
                        OrderTimeline.of(order),
                        "{\"primary_cause\":\"商家出餐慢\",\"confidence\":\"HIGH\"}",
                        emptyRetrieval(),
                        true);

        assertEquals(false, result.get("model_output_parsed"));
        assertTrue((Boolean) result.get("needs_human_review"));
    }

    private static OrderInfo order() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 12, 0);
        return OrderInfo.builder()
                .orderId("order-1")
                .createTime(created)
                .acceptTime(created.plusMinutes(2))
                .arriveShopTime(created.plusMinutes(7))
                .pickupTime(created.plusMinutes(12))
                .deliveryTime(created.plusMinutes(30))
                .expectedDeliveryTime(created.plusMinutes(35))
                .build();
    }

    private static Map<String, Object> ruleDecision() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("approval_required", false);
        decision.put("should_compensate", true);
        decision.put("suggested_amount", 5);
        decision.put("authoritative_amount", new BigDecimal("5.00"));
        decision.put("decision_status", "DETERMINED");
        decision.put("currency", "CNY");
        decision.put("suggested_method", "coupon");
        decision.put("matched_rules", Collections.singletonList(Map.of("rule_id", "COMP-001")));
        return decision;
    }

    private static RetrievalService.RetrievalResult emptyRetrieval() {
        return RetrievalService.RetrievalResult.builder()
                .query("test")
                .items(Collections.emptyList())
                .totalFound(0)
                .build();
    }
}
