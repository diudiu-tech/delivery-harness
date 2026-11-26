package com.delivery.harness.platform.operations;

import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.store.PlatformStore;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class OperationsService {
    private final PlatformStore store;

    public OperationsService(PlatformStore store) {
        this.store = store;
    }

    public Map<String, Object> metrics(Principal actor) {
        actor.requireRole("OPERATOR", "QUALITY", "AUDITOR", "ADMIN");
        List<PlatformStore.Entity> executions = store.scopedSummaryRecent(actor, "execution", 1000);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("案件总数", store.countScoped(actor, "case", null));
        summary.put("执行总数", store.countScoped(actor, "execution", null));
        for (String status :
                List.of(
                        "SUCCESS",
                        "DEGRADED",
                        "FAILED",
                        "TIMED_OUT",
                        "CANCELLED",
                        "QUEUED",
                        "RUNNING")) {
            summary.put(status, store.countScoped(actor, "execution", status));
        }
        List<Long> durations =
                executions.stream()
                        .map(e -> duration(e.data()))
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("count", durations.size());
        latency.put("p50_ms", percentile(durations, .5));
        latency.put("p95_ms", percentile(durations, .95));
        latency.put("p99_ms", percentile(durations, .99));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("latency", latency);
        result.put("sample_window", "LATEST_1000_EXECUTIONS");
        result.put(
                "quality_note",
                "Execution completion is separate from decision accuracy; unmeasured model use and"
                        + " cost are unknown");
        Map<String, BigDecimal> costs = new TreeMap<>();
        long known = 0, unknown = 0;
        for (PlatformStore.Entity execution : executions) {
            Object value = execution.data().get("model_call_metadata");
            if (value instanceof Map<?, ?> metadata && knownCost(metadata)) {
                String group = metadata.get("cost_currency") + ":" + metadata.get("price_version");
                costs.merge(
                        group,
                        new BigDecimal(metadata.get("estimated_cost").toString()),
                        BigDecimal::add);
                known++;
            } else unknown++;
        }
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("known_executions", known);
        cost.put("unknown_executions", unknown);
        cost.put(
                "status",
                unknown == 0 && known > 0 ? "KNOWN" : known > 0 ? "PARTIALLY_KNOWN" : "UNKNOWN");
        cost.put("totals_by_currency_and_price_version", costs);
        result.put("model_cost", cost);
        return result;
    }

    public Map<String, Object> audit(Principal actor, int limit, String cursor) {
        actor.requireRole("AUDITOR", "ADMIN");
        PlatformStore.AuditPage page = store.auditPage(actor, cursor, limit);
        return Map.of("items", page.items(), "cursor", page.cursor());
    }

    private static boolean knownCost(Map<?, ?> metadata) {
        if (!"KNOWN".equals(metadata.get("cost_status"))
                || !("CNY".equals(metadata.get("cost_currency"))
                        || "USD".equals(metadata.get("cost_currency")))
                || !(metadata.get("price_version") instanceof String version)
                || version.isBlank()
                || version.length() > 100
                || metadata.get("estimated_cost") == null) return false;
        String amount = metadata.get("estimated_cost").toString();
        if (amount.length() > 60) return false;
        try {
            return new BigDecimal(amount).signum() >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static Long duration(Map<String, Object> data) {
        Object value = data.get("duration_ms");
        if (value == null) value = data.get("total_duration_ms");
        if (value instanceof Number n) return Math.max(0L, n.longValue());
        if (data.get("started_at") != null && data.get("finished_at") != null) {
            try {
                return Math.max(
                        0L,
                        Duration.between(
                                        Instant.parse(data.get("started_at").toString()),
                                        Instant.parse(data.get("finished_at").toString()))
                                .toMillis());
            } catch (DateTimeParseException invalidTime) {
                throw new IllegalStateException(
                        "Stored execution has an invalid timestamp", invalidTime);
            }
        }
        return null;
    }

    static Long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return null;
        return values.get(
                Math.min(
                        values.size() - 1,
                        Math.max(0, (int) Math.ceil(percentile * values.size()) - 1)));
    }
}
