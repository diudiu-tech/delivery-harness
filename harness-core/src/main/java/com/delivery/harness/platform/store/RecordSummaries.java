package com.delivery.harness.platform.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit list projection. Full documents, prompts and evidence never belong in list rows. */
public final class RecordSummaries {
    private static final Set<String> SCALARS =
            Set.of(
                    "name",
                    "title",
                    "status",
                    "state",
                    "created_by",
                    "submitted_by",
                    "published_by",
                    "case_id",
                    "order_id",
                    "scenario",
                    "city_code",
                    "station_id",
                    "risk_level",
                    "assignee",
                    "complaint_type",
                    "policy_version",
                    "config_hash",
                    "dataset_hash",
                    "runtime_config_id",
                    "dataset_version",
                    "model",
                    "deterministic_only",
                    "completed",
                    "total",
                    "duration_ms",
                    "total_duration_ms",
                    "started_at",
                    "finished_at",
                    "source_type",
                    "content_hash",
                    "scorer_version",
                    "category",
                    "feedback_type",
                    "error_code",
                    "decision_id",
                    "execution_id");

    private RecordSummaries() {}

    public static Map<String, Object> of(String kind, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary_schema", "record-summary-v1");
        result.put("summary_scope_valid", true);
        SCALARS.forEach(key -> scalar(result, key, data.get(key)));
        for (String key : List.of("city_codes", "station_ids")) {
            if (data.containsKey(key)) {
                Object values = scopeValues(data.get(key));
                result.put(key, values);
                if (!(values instanceof Collection<?>)) result.put("summary_scope_valid", false);
            }
        }
        if (data.get("rules") instanceof Collection<?> rules)
            result.put("rule_count", rules.size());
        if (data.get("chunks") instanceof Collection<?> chunks)
            result.put("chunk_count", chunks.size());
        if ("runtime_config".equals(kind)) result.putAll(runtimeScopes(data));
        if ("eval_dataset".equals(kind)) result.put("evidence_scopes", evidenceScopes(data));
        if ("eval_run".equals(kind)) {
            result.put("runtime_visibility", runtimeScopes(map(data.get("resolved_config"))));
            result.put("evidence_scopes", evidenceScopes(map(data.get("dataset_snapshot"))));
        }
        if (data.get("model_call_metadata") instanceof Map<?, ?> metadata) {
            Map<String, Object> cost = new LinkedHashMap<>();
            for (String key :
                    List.of("cost_status", "estimated_cost", "cost_currency", "price_version")) {
                scalar(cost, key, metadata.get(key));
            }
            result.put("model_call_metadata", cost);
        }
        return result;
    }

    private static Map<String, Object> runtimeScopes(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy_scope", publicationScope(data.get("policy_scope")));
        Object documents = data.get("knowledge_snapshot");
        if (documents instanceof Collection<?> values && values.size() <= 50) {
            result.put(
                    "knowledge_scopes",
                    values.stream().map(RecordSummaries::publicationScope).toList());
        }
        return result;
    }

    private static Map<String, Object> publicationScope(Object value) {
        Map<String, Object> source = map(value);
        Map<String, Object> scope = new LinkedHashMap<>();
        for (String key : List.of("city_codes", "station_ids")) {
            if (source.containsKey(key)) scope.put(key, scopeValues(source.get(key)));
        }
        return scope;
    }

    private static Object scopeValues(Object value) {
        if (!(value instanceof Collection<?> values)
                || values.size() > 1000
                || values.stream()
                        .anyMatch(
                                v ->
                                        !(v instanceof String text)
                                                || text.isBlank()
                                                || text.length() > 100)) {
            // Preserve invalidity so visibility checks fail closed; never truncate an authorization
            // scope.
            return Map.of("invalid_scope", true);
        }
        return List.copyOf(values);
    }

    private static List<Map<String, Object>> evidenceScopes(Map<String, Object> dataset) {
        if (!(dataset.get("samples") instanceof Collection<?> samples) || samples.size() > 100) {
            return List.of(Map.of());
        }
        List<Map<String, Object>> scopes = new ArrayList<>();
        for (Object sample : samples) {
            Map<String, Object> order = map(map(sample).get("evidence_order"));
            Map<String, Object> scope = new LinkedHashMap<>();
            scalar(scope, "city_code", order.get("cityCode"));
            scalar(scope, "station_id", order.get("stationId"));
            scopes.add(scope);
        }
        return scopes;
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> entries) {
            entries.forEach(
                    (key, item) -> {
                        if (key instanceof String text) result.put(text, item);
                    });
        }
        return result;
    }

    private static void scalar(Map<String, Object> result, String key, Object value) {
        if (value instanceof String text && text.length() <= 4096
                || value instanceof Number
                || value instanceof Boolean) result.put(key, value);
    }
}
