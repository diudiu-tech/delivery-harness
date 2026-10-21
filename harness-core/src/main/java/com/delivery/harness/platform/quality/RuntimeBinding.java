package com.delivery.harness.platform.quality;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;

import java.util.List;
import java.util.Map;

/** An execution-scoped immutable binding. It must always be closed on worker reuse. */
public final class RuntimeBinding implements AutoCloseable {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();
    private final Snapshot previous;
    private final boolean previousDamage;
    private final Map<String, Object> previousUsage;
    private static final ThreadLocal<Boolean> DAMAGE = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> USAGE = new ThreadLocal<>();

    public record Snapshot(String configId, String configHash, String model, String systemPrompt,
                           double temperature, int maxTokens, int timeoutSeconds, int maxRetries,
                           boolean deterministicOnly, List<RuleInfo> rules,
                           List<Map<String, Object>> knowledge, OrderInfo order) {}

    private RuntimeBinding(Snapshot snapshot) {
        previous = CURRENT.get();
        previousDamage = Boolean.TRUE.equals(DAMAGE.get());
        previousUsage = USAGE.get();
        CURRENT.set(snapshot);
        USAGE.set(new java.util.LinkedHashMap<>(Map.of("usage_status", "UNKNOWN", "cost_status", "UNKNOWN")));
    }

    public static RuntimeBinding open(Snapshot snapshot) {
        PersonalDataGuard.requireClean("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", snapshot.systemPrompt());
        if (snapshot.knowledge() != null) {
            snapshot.knowledge().forEach(KnowledgeService::requirePrivacySafe);
        }
        if (snapshot.rules() != null) {
            snapshot.rules().forEach(RuntimeBinding::requireRulePrivacySafe);
        }
        return new RuntimeBinding(snapshot);
    }

    static void requireRulePrivacySafe(RuleInfo rule) {
        PersonalDataGuard.requireClean("POLICY_CONTAINS_PERSONAL_DATA", rule.getRuleName(), rule.getContent());
    }

    public static RuntimeBinding bind(OrderInfo order, List<RuleInfo> rules, Map<String, Object> config, boolean verifiedDamage) {
        List<RuleInfo> frozenRules = rules == null ? PolicyService.ruleMaps(config.get("policy_snapshot")).stream()
                .map(rule -> JsonUtil.fromMap(rule, RuleInfo.class)).toList() : rules.stream()
                .map(rule -> JsonUtil.fromMap(JsonUtil.toMap(rule), RuleInfo.class)).toList();
        RuntimeBinding binding = open(new Snapshot(QualityData.text(config, "config_id", ""),
                QualityData.text(config, "config_hash", ""), QualityData.text(config, "model", "configured-default"),
                QualityData.text(config, "prompt", ""), Double.parseDouble(QualityData.text(config, "temperature", "0")),
                Integer.parseInt(QualityData.text(config, "max_tokens", "2048")),
                Integer.parseInt(QualityData.text(config, "timeout_seconds", "30")),
                Integer.parseInt(QualityData.text(config, "max_retries", "0")),
                Boolean.TRUE.equals(config.get("deterministic_only")), frozenRules,
                PolicyService.ruleMaps(config.get("knowledge_snapshot")), order == null ? null : JsonUtil.fromMap(JsonUtil.toMap(order), OrderInfo.class)));
        DAMAGE.set(verifiedDamage);
        recordCall(Map.of("policy_version", QualityData.text(config, "policy_version", "")));
        if (config.get("pricing") instanceof Map<?, ?>) {
            recordCall(Map.of("pricing", config.get("pricing")));
        }
        return binding;
    }

    public static String policyVersion() {
        return USAGE.get() == null ? null : QualityData.text(USAGE.get(), "policy_version", "");
    }

    public static boolean damageEvidenceVerified() {
        return Boolean.TRUE.equals(DAMAGE.get());
    }

    public static void recordCall(Map<String, Object> values) {
        if (USAGE.get() != null) {
            USAGE.get().putAll(values);
            Map<String, Object> data = USAGE.get();
            Map<String, Object> pricing = QualityData.map(data.get("pricing"));
            if ("KNOWN".equals(data.get("usage_status")) && !pricing.isEmpty()
                    && data.get("prompt_tokens") instanceof Number prompt && data.get("completion_tokens") instanceof Number completion) {
                java.math.BigDecimal input = new java.math.BigDecimal(pricing.get("input_per_million").toString())
                        .multiply(java.math.BigDecimal.valueOf(prompt.longValue()));
                java.math.BigDecimal output = new java.math.BigDecimal(pricing.get("output_per_million").toString())
                        .multiply(java.math.BigDecimal.valueOf(completion.longValue()));
                data.put("cost_status", "KNOWN");
                data.put("estimated_cost", input.add(output).divide(new java.math.BigDecimal("1000000")).toPlainString());
                data.put("cost_currency", pricing.get("currency"));
                data.put("price_version", pricing.get("version"));
            }
        }
    }

    public static Map<String, Object> callMetadata() {
        return USAGE.get() == null ? Map.of("usage_status", "UNKNOWN", "cost_status", "UNKNOWN")
                : QualityData.copy(USAGE.get());
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static boolean deterministicOnly() {
        return CURRENT.get() != null && CURRENT.get().deterministicOnly();
    }

    public static List<RuleInfo> policyRules() {
        return CURRENT.get() == null ? null : CURRENT.get().rules();
    }

    public static OrderInfo evidenceOrder() {
        return CURRENT.get() == null ? null : CURRENT.get().order();
    }

    public static java.time.Instant asOf() {
        OrderInfo order = evidenceOrder();
        return order == null || order.getDeliveryTime() == null ? java.time.Instant.now()
                : order.getDeliveryTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    }

    @Override
    public void close() {
        DAMAGE.set(previousDamage);
        if (previousUsage == null) {
            USAGE.remove();
        } else {
            USAGE.set(previousUsage);
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
