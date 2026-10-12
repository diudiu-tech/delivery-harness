package com.delivery.harness.tool.compensation;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.quality.RuntimeBinding;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_COMPENSATION_RULE;

/** Authoritative, versioned compensation calculations. Unverified v1 candidates never become v2 decisions. */
@Component
@RequiredArgsConstructor
public class CompensationRuleToolkit {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final com.delivery.harness.tool.gateway.ToolGateway toolGateway;
    private final RuleBaseService ruleBaseService;
    @Value("${harness.compensation.approval-threshold:10.0}")
    private BigDecimal approvalThreshold = BigDecimal.TEN;

    @PostConstruct
    public void init() { toolGateway.register(TOOL_COMPENSATION_RULE, buildDefinition(), this::matchRules); }

    private ToolResult matchRules(Map<String, Object> params) {
        String complaint = text(params.get("complaint_type"), "OVERTIME").toUpperCase(Locale.ROOT);
        String damage = text(params.get("damage_level"), "").toUpperCase(Locale.ROOT);
        String currency = text(params.get("currency"), "CNY");
        BigDecimal amount = decimal(params.get("order_amount"));
        Long seconds = longValue(params.get("overtime_seconds"));
        Long minutes = seconds == null ? longValue(params.get("overtime_minutes")) : Long.valueOf(seconds / 60);
        boolean unknownOvertime = "OVERTIME".equals(complaint) && (minutes == null || minutes < 0
                || (seconds != null && seconds < 0)
                || (params.get("overtime_seconds") != null && seconds == null)
                || (params.containsKey("overtime_status") && !List.of("ON_TIME", "LATE").contains(params.get("overtime_status")))
                || "INVALID".equals(params.get("timeline_quality")));
        boolean invalidAmount = amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2;
        Instant policyAt;
        try { policyAt = params.get("policy_at") == null ? Instant.now() : Instant.parse(params.get("policy_at").toString()); }
        catch (RuntimeException e) { return failed("Invalid policy_at"); }
        List<String> reasons = new ArrayList<>();
        String status = "DETERMINED";
        if (unknownOvertime) { status = "INSUFFICIENT_EVIDENCE"; reasons.add("overtime_unknown_or_invalid"); }
        if (invalidAmount) { status = "INSUFFICIENT_EVIDENCE"; reasons.add("order_amount_missing_or_invalid"); }
        if (!"CNY".equals(currency)) { status = "OUT_OF_SCOPE"; reasons.add("unsupported_currency"); }
        List<RuleInfo> matched = ruleBaseService.findByTypeAt("compensation", policyAt).stream()
                .filter(r -> matches(r, complaint, minutes)).toList();
        RuleInfo rule = matched.isEmpty() ? null : matched.get(0);
        if (rule == null && "DETERMINED".equals(status)) { status = "OUT_OF_SCOPE"; reasons.add("no_applicable_policy"); }
        if (rule != null && matched.size() > 1 && priority(rule) == priority(matched.get(1))) {
            status = "POLICY_CONFLICT"; reasons.add("equal_priority_policy_overlap");
        }
        Map<String, Object> actions = rule == null || rule.getActions() == null ? Map.of() : rule.getActions();
        String amountType = text(actions.get("amount_type"), "MANUAL");
        BigDecimal candidate = invalidAmount || rule == null ? ZERO : calculate(amountType, amount, damage, actions);
        if ("DAMAGE_LEVEL".equals(amountType) && (!Boolean.TRUE.equals(params.get("damage_evidence_verified"))
                || !List.of("FULL", "PARTIAL").contains(damage))) {
            status = "INSUFFICIENT_EVIDENCE"; reasons.add("damage_material_not_verified");
        }
        if ("MANUAL".equals(amountType) && rule != null) { status = "INSUFFICIENT_EVIDENCE"; reasons.add("manual_policy"); }
        if (candidate == null || candidate.signum() < 0 || candidate.stripTrailingZeros().scale() > 2) {
            status = "POLICY_CONFLICT"; reasons.add("invalid_policy_amount"); candidate = ZERO;
        }
        candidate = candidate.setScale(2, RoundingMode.HALF_UP);
        boolean cash = text(actions.get("method"), "").contains("退款");
        BigDecimal refundable = decimal(params.getOrDefault("refundable_balance", amount));
        if (cash && "DETERMINED".equals(status) && (refundable == null || candidate.compareTo(refundable) > 0)) {
            status = "POLICY_CONFLICT"; reasons.add("cash_exceeds_refundable_balance");
        }
        BigDecimal authoritative = "DETERMINED".equals(status) ? candidate : null;
        boolean approval = authoritative == null || Boolean.TRUE.equals(actions.get("approval_required"))
                || candidate.compareTo(approvalThreshold) >= 0;
        if (Boolean.TRUE.equals(actions.get("approval_required"))) { reasons.add("policy_requires_human_review"); }
        if (candidate.compareTo(approvalThreshold) >= 0) { reasons.add("approval_threshold_reached"); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complaint_type", complaint);
        result.put("overtime_minutes", minutes);
        result.put("overtime_seconds", seconds);
        result.put("order_amount", amount);
        result.put("damage_level", damage.isBlank() ? null : damage);
        result.put("matched_rules", metadata("POLICY_CONFLICT".equals(status) ? matched : rule == null ? List.of() : List.of(rule)));
        result.put("should_compensate", candidate.signum() > 0);
        result.put("suggested_amount", candidate);
        result.put("authoritative_amount", authoritative);
        result.put("amount_is_candidate", authoritative == null);
        result.put("decision_status", status);
        result.put("decision_reasons", reasons);
        result.put("suggested_method", authoritative == null ? "需人工判断" : text(actions.get("method"), "需人工判断"));
        result.put("confidence", text(actions.get("confidence"), "LOW"));
        result.put("approval_required", approval);
        result.put("approval_reason", String.join(",", reasons));
        result.put("decided_by", "rule_engine");
        result.put("policy_source", "rule_base");
        result.put("policy_version", RuntimeBinding.current() != null ? RuntimeBinding.policyVersion()
                : rule == null ? null : text(rule.getVersion(), "1.0.0"));
        result.put("input_snapshot_hash", inputHash(params));
        result.put("currency", currency);
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("amount_type", amountType);
        calculation.put("base", amount);
        calculation.put("raw_overtime_seconds", seconds);
        calculation.put("tier_minutes", minutes);
        calculation.put("minute_rounding", "FLOOR");
        calculation.put("amount_rounding", "HALF_UP_2_DECIMAL_BEFORE_CAP");
        calculation.put("parameters", actions);
        calculation.put("policy_at", policyAt.toString());
        calculation.put("candidate_amount", candidate);
        result.put("calculation", calculation);
        return ToolResult.builder().toolName(TOOL_COMPENSATION_RULE).success(true).data(result).build();
    }

    private static boolean matches(RuleInfo rule, String complaint, Long minutes) {
        Map<String, Object> c = rule.getConditions();
        if (c == null || !complaint.equalsIgnoreCase(text(c.get("complaint_type"), ""))) { return false; }
        Long min = longValue(c.get("min_overtime_minutes"));
        Long max = longValue(c.get("max_overtime_minutes"));
        if ((min != null || max != null) && minutes == null) { return false; }
        return (min == null || minutes >= min) && (max == null || minutes <= max);
    }
    private static int priority(RuleInfo rule) { return rule.getPriority() == null ? 0 : rule.getPriority(); }
    private static BigDecimal calculate(String type, BigDecimal amount, String damage, Map<String, Object> a) {
        return switch (type) {
            case "FIXED" -> decimal(a.get("amount"));
            case "PERCENT_CAPPED" -> percentage(amount, decimal(a.get("rate")), decimal(a.get("cap")));
            case "DAMAGE_LEVEL" -> "FULL".equals(damage) ? amount : "PARTIAL".equals(damage)
                    ? percentage(amount, decimal(a.get("partial_rate")), decimal(a.get("partial_cap"))) : ZERO;
            default -> ZERO;
        };
    }
    private static BigDecimal percentage(BigDecimal amount, BigDecimal rate, BigDecimal cap) {
        return rate == null || cap == null ? null : amount.multiply(rate).setScale(2, RoundingMode.HALF_UP).min(cap);
    }
    private static List<Map<String, Object>> metadata(List<RuleInfo> rules) {
        return rules.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule_id", r.getRuleId()); item.put("rule_name", r.getRuleName());
            item.put("rule_version", text(r.getVersion(), "1.0.0")); item.put("content", r.getContent());
            item.put("category", r.getCategory()); item.put("priority", r.getPriority());
            item.put("policy_summary", r.getConditions() + "; " + r.getActions());
            return item;
        }).toList();
    }
    private static String inputHash(Map<String, Object> input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                    com.delivery.harness.common.util.JsonUtil.toJson(new java.util.TreeMap<>(input))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static BigDecimal decimal(Object raw) {
        try { return raw == null ? null : new BigDecimal(raw.toString()); }
        catch (NumberFormatException e) { return null; }
    }
    private static Long longValue(Object raw) {
        try { return raw == null ? null : new BigDecimal(raw.toString()).longValueExact(); }
        catch (RuntimeException e) { return null; }
    }
    private static String text(Object raw, String fallback) { return raw == null || raw.toString().isBlank() ? fallback : raw.toString(); }
    private static ToolResult failed(String reason) { return ToolResult.builder().toolName(TOOL_COMPENSATION_RULE).success(false).errorMessage(reason).build(); }
    private ToolDefinition buildDefinition() {
        Map<String, ToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();
        parameters.put("complaint_type", ToolDefinition.ParameterDef.builder().name("complaint_type").type("string")
                .description("投诉类型").required(true).enumValues(List.of("OVERTIME", "WRONG_ORDER", "DAMAGED", "MISSING_ITEM")).build());
        for (String field : List.of("overtime_minutes", "overtime_seconds", "order_amount", "damage_level", "currency")) {
            String type = field.startsWith("overtime_") ? "integer" : "order_amount".equals(field) ? "number" : "string";
            parameters.put(field, ToolDefinition.ParameterDef.builder().name(field).type(type).required(false).build());
        }
        return ToolDefinition.builder().toolName(TOOL_COMPENSATION_RULE).description("版本化权威赔付决定，未知值进入人工")
                .category("compensation").parameters(parameters).build();
    }
}
