package com.delivery.harness.runtime;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.exception.RuntimeBoundaryException;
import com.delivery.harness.common.util.JsonUtil;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable execution binding. Platform adapters own authorization; the core owns safe
 * consumption.
 */
public final class RuntimeBinding implements AutoCloseable {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DAMAGE = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> USAGE = new ThreadLocal<>();
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Snapshot snapshot;
    private final Snapshot previous;
    private final Boolean previousDamage;
    private final Map<String, Object> previousUsage;
    private final Long previousDeadline;
    private boolean closed;

    /**
     * systemPrompt remains the configuration prompt accessor for API compatibility, not a
     * system-role grant.
     */
    public record Snapshot(
            String configId,
            String configHash,
            String model,
            String systemPrompt,
            double temperature,
            int maxTokens,
            int timeoutSeconds,
            int maxRetries,
            boolean deterministicOnly,
            List<RuleInfo> rules,
            List<Map<String, Object>> knowledge,
            OrderInfo order) {
        public Snapshot {
            rules = copyRules(rules);
            knowledge = copyKnowledge(knowledge);
            order = copyOrder(order);
        }

        @Override
        public List<RuleInfo> rules() {
            return copyRules(rules);
        }

        @Override
        public List<Map<String, Object>> knowledge() {
            return copyKnowledge(knowledge);
        }

        @Override
        public OrderInfo order() {
            return copyOrder(order);
        }
    }

    private RuntimeBinding(Snapshot snapshot) {
        this.snapshot = snapshot;
        previous = CURRENT.get();
        previousDamage = DAMAGE.get();
        previousUsage = USAGE.get();
        previousDeadline = DEADLINE.get();
        CURRENT.set(snapshot);
        DAMAGE.remove();
        USAGE.set(new LinkedHashMap<>(Map.of("usage_status", "UNKNOWN", "cost_status", "UNKNOWN")));
    }

    public static RuntimeBinding open(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Runtime snapshot is required");
        }
        PersonalDataGuard.requireClean(
                "RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", snapshot.systemPrompt());
        if (snapshot.knowledge() != null) {
            snapshot.knowledge().forEach(PersonalDataGuard::requireKnowledgeSafe);
        }
        if (snapshot.rules() != null) {
            snapshot.rules().forEach(RuntimeBinding::requireRulePrivacySafe);
        }
        return new RuntimeBinding(snapshot);
    }

    public static void requireRulePrivacySafe(RuleInfo rule) {
        PersonalDataGuard.requireClean(
                "POLICY_CONTAINS_PERSONAL_DATA", rule.getRuleName(), rule.getContent());
    }

    public static RuntimeBinding bind(
            OrderInfo order,
            List<RuleInfo> rules,
            Map<String, Object> config,
            boolean verifiedDamage) {
        List<RuleInfo> frozenRules =
                rules == null
                        ? RuntimeData.maps(config.get("policy_snapshot")).stream()
                                .map(rule -> JsonUtil.fromMap(rule, RuleInfo.class))
                                .toList()
                        : copyRules(rules);
        Snapshot snapshot =
                new Snapshot(
                        RuntimeData.text(config, "config_id", ""),
                        RuntimeData.text(config, "config_hash", ""),
                        RuntimeData.text(config, "model", "configured-default"),
                        RuntimeData.text(config, "prompt", ""),
                        Double.parseDouble(RuntimeData.text(config, "temperature", "0")),
                        Integer.parseInt(RuntimeData.text(config, "max_tokens", "2048")),
                        Integer.parseInt(RuntimeData.text(config, "timeout_seconds", "30")),
                        Integer.parseInt(RuntimeData.text(config, "max_retries", "0")),
                        Boolean.TRUE.equals(config.get("deterministic_only")),
                        frozenRules,
                        RuntimeData.maps(config.get("knowledge_snapshot")),
                        order);
        RuntimeBinding binding = open(snapshot);
        DAMAGE.set(verifiedDamage);
        recordCall(Map.of("policy_version", RuntimeData.text(config, "policy_version", "")));
        if (config.get("pricing") instanceof Map<?, ?>) {
            recordCall(Map.of("pricing", config.get("pricing")));
        }
        return binding;
    }

    /**
     * A v2 execution must never silently fall through to an unbound demo order or live mutable rule
     * store.
     */
    public static void requireWorkflowContext() {
        Snapshot context = CURRENT.get();
        OrderInfo order = context == null ? null : context.order();
        if (context == null
                || order == null
                || order.getOrderId() == null
                || order.getOrderId().isBlank()
                || context.rules() == null
                || context.knowledge() == null
                || context.configId() == null
                || context.configId().isBlank()
                || context.configHash() == null
                || context.configHash().isBlank()) {
            throw new RuntimeBoundaryException(
                    409,
                    "RUNTIME_BINDING_REQUIRED",
                    "Trusted execution requires a frozen order, policy and runtime configuration");
        }
    }

    public static String policyVersion() {
        return USAGE.get() == null ? null : RuntimeData.text(USAGE.get(), "policy_version", "");
    }

    public static boolean damageEvidenceVerified() {
        return Boolean.TRUE.equals(DAMAGE.get());
    }

    /**
     * Adds the online execution's remaining total budget without changing its frozen configuration
     * hash.
     */
    public static void limitDeadline(long absoluteNanoTime) {
        if (CURRENT.get() == null) {
            throw new RuntimeBoundaryException(
                    409,
                    "RUNTIME_BINDING_REQUIRED",
                    "A request deadline requires a trusted runtime binding");
        }
        Long existing = DEADLINE.get();
        DEADLINE.set(existing == null ? absoluteNanoTime : Math.min(existing, absoluteNanoTime));
    }

    public static long requestDeadline(long candidateDeadline) {
        Long executionDeadline = DEADLINE.get();
        return executionDeadline == null
                ? candidateDeadline
                : Math.min(executionDeadline, candidateDeadline);
    }

    public static void recordCall(Map<String, Object> values) {
        Map<String, Object> data = USAGE.get();
        if (data == null) {
            return;
        }
        data.putAll(RuntimeData.copy(values));
        Map<String, Object> pricing = RuntimeData.map(data.get("pricing"));
        if ("KNOWN".equals(data.get("usage_status"))
                && !pricing.isEmpty()
                && data.get("prompt_tokens") instanceof Number prompt
                && data.get("completion_tokens") instanceof Number completion) {
            BigDecimal input =
                    new BigDecimal(pricing.get("input_per_million").toString())
                            .multiply(BigDecimal.valueOf(prompt.longValue()));
            BigDecimal output =
                    new BigDecimal(pricing.get("output_per_million").toString())
                            .multiply(BigDecimal.valueOf(completion.longValue()));
            data.put("cost_status", "KNOWN");
            data.put(
                    "estimated_cost",
                    input.add(output).divide(new BigDecimal("1000000")).toPlainString());
            data.put("cost_currency", pricing.get("currency"));
            data.put("price_version", pricing.get("version"));
        }
    }

    public static Map<String, Object> callMetadata() {
        return USAGE.get() == null
                ? Map.of("usage_status", "UNKNOWN", "cost_status", "UNKNOWN")
                : RuntimeData.copy(USAGE.get());
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

    public static Instant asOf() {
        OrderInfo order = evidenceOrder();
        return order == null || order.getDeliveryTime() == null
                ? Instant.now()
                : order.getDeliveryTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (CURRENT.get() != snapshot) {
            throw new IllegalStateException("Runtime bindings must close in reverse order");
        }
        restore(DAMAGE, previousDamage);
        restore(USAGE, previousUsage);
        restore(CURRENT, previous);
        restore(DEADLINE, previousDeadline);
        closed = true;
    }

    private static <T> void restore(ThreadLocal<T> local, T value) {
        if (value == null) {
            local.remove();
        } else {
            local.set(value);
        }
    }

    private static OrderInfo copyOrder(OrderInfo order) {
        return order == null ? null : JsonUtil.fromMap(JsonUtil.toMap(order), OrderInfo.class);
    }

    private static List<RuleInfo> copyRules(List<RuleInfo> rules) {
        return rules == null
                ? null
                : rules.stream()
                        .map(rule -> JsonUtil.fromMap(JsonUtil.toMap(rule), RuleInfo.class))
                        .toList();
    }

    private static List<Map<String, Object>> copyKnowledge(List<Map<String, Object>> knowledge) {
        return knowledge == null ? null : knowledge.stream().map(RuntimeData::copy).toList();
    }
}
