package com.delivery.harness.tool.compensation;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_COMPENSATION_RULE;

/**
 * Mock {@code compensation_rule} tool, and the authoritative source of the
 * compensation amount.
 *
 * <p>The amount is decided here, deterministically, from the complaint type
 * and the observed overtime. The language model is never asked to produce it —
 * see {@code docs/adr/0002-rule-engine-owns-the-compensation-amount.md}. The
 * result carries the matched rule so the decision can be audited back to a
 * policy line rather than to a sampled token sequence.
 */
@Component
@RequiredArgsConstructor
public class CompensationRuleToolkit {

    /** Percentage of order value paid out on the top overtime tier. */
    private static final BigDecimal SEVERE_OVERTIME_RATE = new BigDecimal("0.50");

    /** Hard ceiling on a percentage-based payout, in yuan. */
    private static final BigDecimal SEVERE_OVERTIME_CAP = new BigDecimal("20.00");

    private static final BigDecimal MODERATE_OVERTIME_COUPON = new BigDecimal("5.00");
    private static final BigDecimal MINOR_OVERTIME_REDPACKET = new BigDecimal("1.00");
    private static final BigDecimal WRONG_ORDER_COUPON = new BigDecimal("5.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final ToolGateway toolGateway;

    /** Payouts at or above this value require a human approver. */
    @Value("${harness.compensation.approval-threshold:10.0}")
    private BigDecimal approvalThreshold;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_COMPENSATION_RULE, buildDefinition(), this::matchRules);
    }

    private ToolResult matchRules(Map<String, Object> params) {
        String complaintType = asString(params.get("complaint_type"), "OVERTIME");
        int overtimeMinutes = Math.max(0, asInt(params.get("overtime_minutes"), 0));
        BigDecimal orderAmount = asDecimal(params.get("order_amount"), ZERO);

        Decision decision = decide(complaintType, overtimeMinutes, orderAmount);
        boolean approvalRequired = decision.approvalRequired()
                || decision.amount().compareTo(approvalThreshold) >= 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complaint_type", complaintType);
        result.put("overtime_minutes", overtimeMinutes);
        result.put("order_amount", orderAmount);
        result.put("matched_rules", decision.rules());
        result.put("should_compensate", decision.amount().compareTo(ZERO) > 0);
        result.put("suggested_amount", decision.amount());
        result.put("suggested_method", decision.method());
        result.put("confidence", decision.confidence());
        result.put("approval_required", approvalRequired);
        result.put("approval_reason", approvalReason(decision, approvalRequired));
        result.put("decided_by", "rule_engine");
        return ToolResult.builder().toolName(TOOL_COMPENSATION_RULE).success(true).data(result).build();
    }

    private Decision decide(String complaintType, int overtimeMinutes, BigDecimal orderAmount) {
        switch (complaintType) {
            case "OVERTIME":
                if (overtimeMinutes > 30) {
                    return new Decision(
                            rule("COMP-001", "严重超时赔付", "超时30分钟以上，赔付订单金额的50%，最高不超过20元"),
                            percentageOf(orderAmount, SEVERE_OVERTIME_RATE, SEVERE_OVERTIME_CAP),
                            "原路退款", "HIGH", false);
                }
                if (overtimeMinutes > 15) {
                    return new Decision(
                            rule("COMP-002", "一般超时赔付", "超时15-30分钟，赔付5元优惠券"),
                            MODERATE_OVERTIME_COUPON, "发放优惠券", "HIGH", false);
                }
                if (overtimeMinutes > 0) {
                    return new Decision(
                            rule("COMP-003", "轻微超时赔付", "超时15分钟以内，致歉并赠送1元红包"),
                            MINOR_OVERTIME_REDPACKET, "红包", "MEDIUM", false);
                }
                return new Decision(
                        Collections.emptyList(), ZERO, "无需赔付", "HIGH", false);
            case "DAMAGED":
                return new Decision(
                        rule("COMP-004", "餐品洒漏赔付", "餐品洒漏导致不可食用，全额退款；部分洒漏，赔付50%"),
                        percentageOf(orderAmount, SEVERE_OVERTIME_RATE, SEVERE_OVERTIME_CAP),
                        "原路退款", "LOW", true);
            case "WRONG_ORDER":
                return new Decision(
                        rule("COMP-005", "错单赔付", "送错订单，优先补送正确订单，同时赔付5元优惠券"),
                        WRONG_ORDER_COUPON, "补送并发放优惠券", "MEDIUM", false);
            default:
                return new Decision(
                        Collections.emptyList(), ZERO, "需人工判断", "LOW", true);
        }
    }

    private static BigDecimal percentageOf(BigDecimal orderAmount, BigDecimal rate, BigDecimal cap) {
        BigDecimal raw = orderAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return raw.min(cap);
    }

    private String approvalReason(Decision decision, boolean approvalRequired) {
        if (!approvalRequired) {
            return "";
        }
        if (decision.approvalRequired()) {
            return "投诉类型需人工核实证据后才能定责";
        }
        return "赔付金额达到 " + approvalThreshold.toPlainString() + " 元审批线";
    }

    private static List<Map<String, Object>> rule(String ruleId, String ruleName, String content) {
        Map<String, Object> matched = new LinkedHashMap<>();
        matched.put("rule_id", ruleId);
        matched.put("rule_name", ruleName);
        matched.put("content", content);
        List<Map<String, Object>> rules = new ArrayList<>(1);
        rules.add(matched);
        return rules;
    }

    private static String asString(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static BigDecimal asDecimal(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private record Decision(
            List<Map<String, Object>> rules,
            BigDecimal amount,
            String method,
            String confidence,
            boolean approvalRequired) {}

    private ToolDefinition buildDefinition() {
        Map<String, ToolDefinition.ParameterDef> parameters = new HashMap<>();
        parameters.put("complaint_type", ToolDefinition.ParameterDef.builder()
                .name("complaint_type").type("string").description("投诉类型").required(true)
                .enumValues(Arrays.asList("OVERTIME", "WRONG_ORDER", "DAMAGED", "MISSING_ITEM")).build());
        parameters.put("overtime_minutes", ToolDefinition.ParameterDef.builder()
                .name("overtime_minutes").type("integer").description("实际超时分钟数").required(false).build());
        parameters.put("order_amount", ToolDefinition.ParameterDef.builder()
                .name("order_amount").type("number").description("订单金额，用于按比例赔付").required(false).build());

        return ToolDefinition.builder()
                .toolName(TOOL_COMPENSATION_RULE)
                .description("匹配赔付规则并给出权威赔付金额（决策来源，非模型生成）")
                .category("compensation")
                .parameters(parameters)
                .build();
    }
}
