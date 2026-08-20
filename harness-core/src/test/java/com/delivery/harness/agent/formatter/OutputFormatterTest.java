package com.delivery.harness.agent.formatter;

import com.delivery.harness.agent.analysis.OrderTimeline;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputFormatterTest {

    private final OutputFormatter formatter = new OutputFormatter(new StructuredOutputParser());

    @Test
    void malformedCompensationSchemaCannotSuppressApproval() {
        Map<String, Object> result = formatter.formatCompensationSuggestion(
                order(), "LATE_DELIVERY", OrderTimeline.of(order()), ruleDecision(),
                "{\"foo\":\"bar\"}", emptyRetrieval(), true);

        assertFalse((Boolean) result.get("model_output_parsed"));
        assertTrue((Boolean) result.get("approval_required"));
        assertTrue(((java.util.List<?>) result.get("approval_reasons"))
                .contains("model_output_unparseable"));
    }

    @Test
    void acceptsCompleteCompensationSchema() {
        String output = "{\"reason\":\"基于超时规则\","
                + "\"customer_message\":\"配送超时，敬请谅解\","
                + "\"risk_warnings\":[],\"escalate\":false,\"confidence\":\"HIGH\"}";

        Map<String, Object> result = formatter.formatCompensationSuggestion(
                order(), "LATE_DELIVERY", OrderTimeline.of(order()), ruleDecision(),
                output, emptyRetrieval(), true);

        assertTrue((Boolean) result.get("model_output_parsed"));
        assertFalse((Boolean) result.get("approval_required"));
    }

    @Test
    void malformedAbnormalSchemaRequiresReview() {
        OrderInfo order = order();
        Map<String, Object> result = formatter.formatAbnormalOrderAnalysis(
                order, OrderTimeline.of(order),
                "{\"primary_cause\":\"商家出餐慢\",\"confidence\":\"HIGH\"}",
                emptyRetrieval(), true);

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
        decision.put("suggested_method", "coupon");
        decision.put("matched_rules", Collections.singletonList("rule-1"));
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
