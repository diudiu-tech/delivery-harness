package com.delivery.harness.tool.compensation;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.policy.AdjudicationStatus;
import com.delivery.harness.policy.ComplaintType;
import com.delivery.harness.policy.DamageLevel;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_COMPENSATION_RULE;

/**
 * {@code compensation_rule} tool backed by {@link SingleSourcePolicyEngine}.
 *
 * <p>All thresholds, caps, approval boundaries, and rule descriptions come
 * strictly from the active {@code PolicyBundle} (ADR-0007).
 */
@Component
@RequiredArgsConstructor
public class CompensationRuleToolkit {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final ToolGateway toolGateway;
    private final SingleSourcePolicyEngine policyEngine;
    private final Clock clock;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_COMPENSATION_RULE, buildDefinition(), this::matchRules);
    }

    private ToolResult matchRules(Map<String, Object> params) {
        Instant observedAt = clock.instant();
        ComplaintType complaintType = ComplaintType.parse(asString(params.get("complaint_type"), "OVERTIME"))
                .orElse(null);
        String rawOrderStatus = asString(params.get("order_status"), "DELIVERED");
        EvidenceValue<String> orderStatus = EvidenceValue.known(rawOrderStatus, "tool_params", observedAt);

        EvidenceValue<Long> overtime = parseLongEvidence(params.get("overtime_minutes"), "overtime_minutes", observedAt);
        EvidenceValue<BigDecimal> orderAmount = parseDecimalEvidence(
                params.get("order_amount"), "order_amount", observedAt);
        EvidenceValue<DamageLevel> damageLevel = parseDamageLevel(params.get("damage_level"), observedAt);
        Instant evaluationAt = parseInstant(params.get("evaluation_at"), observedAt);

        Map<EvidenceType, EvidenceValue<?>> present = new EnumMap<>(EvidenceType.class);
        present.put(EvidenceType.ORDER_STATUS, orderStatus);
        present.put(EvidenceType.ORDER_AMOUNT, orderAmount);
        present.put(EvidenceType.TIMELINE_OVERTIME_MINUTES, overtime);
        present.put(EvidenceType.DAMAGE_LEVEL, damageLevel);
        putPresenceFlag(present, EvidenceType.ORDER_CREATE_TIME,
                params.get("has_create_time"), observedAt);
        putPresenceFlag(present, EvidenceType.ORDER_DELIVERY_TIME,
                params.get("has_delivery_time"), observedAt);
        putPresenceFlag(present, EvidenceType.ORDER_EXPECTED_DELIVERY_TIME,
                params.get("has_expected_delivery_time"), observedAt);

        SingleSourcePolicyEngine.AdjudicationResult adjudication = policyEngine.evaluate(
                new SingleSourcePolicyEngine.PolicyEvaluationInput(
                        orderStatus, complaintType, overtime, orderAmount, damageLevel, evaluationAt, present));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complaint_type", complaintType == null ? params.get("complaint_type") : complaintType.name());
        result.put("overtime_minutes", overtime.knownValue().orElse(null));
        result.put("order_amount", orderAmount.knownValue().orElse(null));
        result.put("damage_level", damageLevel.knownValue().map(Enum::name).orElse(null));
        result.put("adjudication_status", adjudication.status().name());
        result.put("matched_rules", matchedRulesList(adjudication));
        result.put("should_compensate", adjudication.status() == AdjudicationStatus.ELIGIBLE
                && adjudication.actionableAmount() != null
                && adjudication.actionableAmount().compareTo(ZERO) > 0);
        result.put("suggested_amount", adjudication.actionableAmount());
        result.put("calculated_amount", adjudication.calculatedAmount());
        result.put("suggested_method", adjudication.suggestedMethod());
        result.put("approval_required", adjudication.approvalRequired());
        result.put("approval_reason", String.join("；", adjudication.approvalReasons()));
        result.put("missing_evidence", adjudication.missingEvidence());
        result.put("stale_evidence", adjudication.staleEvidence());
        result.put("decided_by", "rule_engine");
        result.put("policy_source", "policy_bundle");
        result.put("policy_version", adjudication.policyVersion());
        result.put("policy_hash", adjudication.policyHash());
        return ToolResult.builder().toolName(TOOL_COMPENSATION_RULE).success(true).data(result).build();
    }

    private static List<Map<String, Object>> matchedRulesList(SingleSourcePolicyEngine.AdjudicationResult adj) {
        if (adj.matchedRuleId() == null) {
            return Collections.emptyList();
        }
        Map<String, Object> matched = new LinkedHashMap<>();
        matched.put("rule_id", adj.matchedRuleId());
        matched.put("rule_name", adj.matchedRuleName());
        matched.put("content", adj.renderedPolicyText());
        return Collections.singletonList(matched);
    }

    private static void putPresenceFlag(
            Map<EvidenceType, EvidenceValue<?>> present, EvidenceType type, Object flag, Instant observedAt) {
        if (Boolean.FALSE.equals(flag)) {
            present.put(type, EvidenceValue.unknown("MISSING_TIMESTAMP", type.name() + " is missing"));
        } else {
            present.put(type, EvidenceValue.known(Boolean.TRUE, "tool_params", observedAt));
        }
    }

    private static EvidenceValue<Long> parseLongEvidence(Object raw, String name, Instant observedAt) {
        if (raw == null) {
            return EvidenceValue.unknown("MISSING_FIELD", name + " is missing");
        }
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value < 0
                    ? EvidenceValue.invalid(String.valueOf(value), name + " is negative")
                    : EvidenceValue.known(value, "tool_params", observedAt);
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                long value = Long.parseLong(text.trim());
                return value < 0
                        ? EvidenceValue.invalid(text, name + " is negative")
                        : EvidenceValue.known(value, "tool_params", observedAt);
            } catch (NumberFormatException e) {
                return EvidenceValue.invalid(text, name + " is not an integer");
            }
        }
        return EvidenceValue.unknown("MISSING_FIELD", name + " is blank");
    }

    private static EvidenceValue<BigDecimal> parseDecimalEvidence(Object raw, String name, Instant observedAt) {
        if (raw == null) {
            return EvidenceValue.unknown("MISSING_FIELD", name + " is missing");
        }
        BigDecimal value;
        if (raw instanceof BigDecimal decimal) {
            value = decimal;
        } else if (raw instanceof Number number) {
            value = BigDecimal.valueOf(number.doubleValue());
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                value = new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                return EvidenceValue.invalid(text, name + " is not numeric");
            }
        } else {
            return EvidenceValue.unknown("MISSING_FIELD", name + " is blank");
        }
        return value.compareTo(BigDecimal.ZERO) < 0
                ? EvidenceValue.invalid(value.toPlainString(), name + " is negative")
                : EvidenceValue.known(value, "tool_params", observedAt);
    }

    private static EvidenceValue<DamageLevel> parseDamageLevel(Object raw, Instant observedAt) {
        if (raw == null || raw.toString().isBlank()) {
            return EvidenceValue.unknown("MISSING_FIELD", "damage_level is missing");
        }
        return DamageLevel.parse(raw.toString())
                .<EvidenceValue<DamageLevel>>map(level -> EvidenceValue.known(level, "tool_params", observedAt))
                .orElseGet(() -> EvidenceValue.invalid(raw.toString(), "unsupported damage_level"));
    }

    private static Instant parseInstant(Object raw, Instant fallback) {
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text.trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

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
                .description("匹配单一策略源 PolicyBundle 并给出权威赔付裁决（非模型生成）")
                .category("compensation")
                .parameters(parameters)
                .build();
    }
}
