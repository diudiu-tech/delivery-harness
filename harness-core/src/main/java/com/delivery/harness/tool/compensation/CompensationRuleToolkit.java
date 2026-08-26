package com.delivery.harness.tool.compensation;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

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

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final ToolGateway toolGateway;
    private final RuleBaseService ruleBaseService;

    /** Payouts at or above this value require a human approver. */
    @Value("${harness.compensation.approval-threshold:10.0}")
    private BigDecimal approvalThreshold;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_COMPENSATION_RULE, buildDefinition(), this::matchRules);
    }

    private ToolResult matchRules(Map<String, Object> params) {
        String complaintType = asString(params.get("complaint_type"), "OVERTIME");
        complaintType = complaintType.toUpperCase(Locale.ROOT);
        int overtimeMinutes = Math.max(0, asInt(params.get("overtime_minutes"), 0));
        BigDecimal orderAmount = asDecimal(params.get("order_amount"), ZERO).max(ZERO);
        String damageLevel = asString(params.get("damage_level"), "").toUpperCase(Locale.ROOT);

        Decision decision = decide(complaintType, overtimeMinutes, orderAmount, damageLevel);
        boolean approvalRequired = decision.approvalRequired()
                || decision.amount().compareTo(approvalThreshold) >= 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complaint_type", complaintType);
        result.put("overtime_minutes", overtimeMinutes);
        result.put("order_amount", orderAmount);
        result.put("damage_level", damageLevel.isBlank() ? null : damageLevel);
        result.put("matched_rules", decision.rules());
        result.put("should_compensate", decision.amount().compareTo(ZERO) > 0);
        result.put("suggested_amount", decision.amount());
        result.put("suggested_method", decision.method());
        result.put("confidence", decision.confidence());
        result.put("approval_required", approvalRequired);
        result.put("approval_reason", approvalReason(decision, approvalRequired));
        result.put("decided_by", "rule_engine");
        result.put("policy_source", "rule_base");
        return ToolResult.builder().toolName(TOOL_COMPENSATION_RULE).success(true).data(result).build();
    }

    private Decision decide(String complaintType, int overtimeMinutes, BigDecimal orderAmount, String damageLevel) {
        Optional<RuleInfo> matched = ruleBaseService.findByType("compensation").stream()
                .filter(rule -> matches(rule, complaintType, overtimeMinutes))
                .findFirst();
        if (matched.isEmpty()) {
            return new Decision(Collections.emptyList(), ZERO, "需人工判断", "LOW", true);
        }

        RuleInfo rule = matched.get();
        Map<String, Object> actions = rule.getActions() == null ? Collections.emptyMap() : rule.getActions();
        String amountType = asString(actions.get("amount_type"), "MANUAL");
        BigDecimal amount = switch (amountType) {
            case "FIXED" -> asDecimal(actions.get("amount"), ZERO);
            case "PERCENT_CAPPED" -> percentageOf(orderAmount, asDecimal(actions.get("rate"), ZERO),
                    asDecimal(actions.get("cap"), orderAmount));
            case "DAMAGE_LEVEL" -> damageAmount(orderAmount, damageLevel, actions);
            default -> ZERO;
        };
        boolean manualDamageReview = "DAMAGE_LEVEL".equals(amountType) && !("FULL".equals(damageLevel) || "PARTIAL".equals(damageLevel));
        return new Decision(ruleMetadata(rule), amount,
                manualDamageReview ? "需人工判断" : asString(actions.get("method"), "需人工判断"),
                asString(actions.get("confidence"), "LOW"),
                manualDamageReview || Boolean.TRUE.equals(actions.get("approval_required")));
    }

    private static boolean matches(RuleInfo rule, String complaintType, int overtimeMinutes) {
        Map<String, Object> conditions = rule.getConditions();
        if (conditions == null) {
            return false;
        }
        if (!complaintType.equalsIgnoreCase(asString(conditions.get("complaint_type"), ""))) {
            return false;
        }
        Integer min = asInteger(conditions.get("min_overtime_minutes"));
        Integer max = asInteger(conditions.get("max_overtime_minutes"));
        return (min == null || overtimeMinutes >= min) && (max == null || overtimeMinutes <= max);
    }

    private static BigDecimal damageAmount(BigDecimal orderAmount, String damageLevel, Map<String, Object> actions) {
        if ("FULL".equals(damageLevel)) {
            return orderAmount;
        }
        if ("PARTIAL".equals(damageLevel)) {
            return percentageOf(orderAmount, asDecimal(actions.get("partial_rate"), ZERO),
                    asDecimal(actions.get("partial_cap"), orderAmount));
        }
        return ZERO;
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

    private static List<Map<String, Object>> ruleMetadata(RuleInfo rule) {
        Map<String, Object> matched = new LinkedHashMap<>();
        matched.put("rule_id", rule.getRuleId());
        matched.put("rule_name", rule.getRuleName());
        matched.put("content", rule.getContent());
        matched.put("category", rule.getCategory());
        matched.put("priority", rule.getPriority());
        return Collections.singletonList(matched);
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

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
        parameters.put("damage_level", ToolDefinition.ParameterDef.builder()
                .name("damage_level").type("string").description("餐品损坏程度，FULL 或 PARTIAL").required(false)
                .enumValues(Arrays.asList("FULL", "PARTIAL")).build());

        return ToolDefinition.builder()
                .toolName(TOOL_COMPENSATION_RULE)
                .description("匹配赔付规则并给出权威赔付金额（决策来源，非模型生成）")
                .category("compensation")
                .parameters(parameters)
                .build();
    }
}
