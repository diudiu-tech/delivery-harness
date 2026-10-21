package com.delivery.harness.runtime;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Full snapshot visibility is stricter than selecting applicable reference chunks for one order.
 */
public final class RuntimeVisibility {
    private RuntimeVisibility() {}

    public static boolean canRead(
            Map<String, Object> config, Set<String> cities, Set<String> stations) {
        return canReadScopes(config, "knowledge_snapshot", cities, stations);
    }

    public static boolean canReadSummary(
            Map<String, Object> summary, Set<String> cities, Set<String> stations) {
        return canReadScopes(summary, "knowledge_scopes", cities, stations);
    }

    private static boolean canReadScopes(
            Map<String, Object> config,
            String knowledgeKey,
            Set<String> cities,
            Set<String> stations) {
        if (!(config.get("policy_scope") instanceof Map<?, ?> policy)
                || !canReadScope(policy, cities, stations)
                || !(config.get(knowledgeKey) instanceof Collection<?> knowledge)) {
            return false;
        }
        for (Object item : knowledge) {
            if (!(item instanceof Map<?, ?> document)
                    || !canReadScope(document, cities, stations)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canReadScope(Map<?, ?> scope, Set<String> cities, Set<String> stations) {
        return covered(scope.get("city_codes"), cities)
                && covered(scope.get("station_ids"), stations);
    }

    private static boolean covered(Object scope, Set<String> allowed) {
        if (!(scope instanceof Collection<?> values) || allowed == null || allowed.isEmpty()) {
            return false;
        }
        for (Object value : values) {
            if (!(value instanceof String text)
                    || text.isBlank()
                    || text.length() > 100
                    || !allowed.contains("*") && !allowed.contains(text)) {
                return false;
            }
        }
        return true;
    }
}
