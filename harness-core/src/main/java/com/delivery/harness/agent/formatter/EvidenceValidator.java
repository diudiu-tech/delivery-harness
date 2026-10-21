package com.delivery.harness.agent.formatter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates references against the exact evidence and rule snapshot used for this execution. */
public final class EvidenceValidator {
    private EvidenceValidator() {}

    public static List<String> validate(
            Map<String, Object> analysis, Map<String, Object> evidence, Set<String> ruleIds) {
        List<String> errors = new ArrayList<>();
        Object chain = analysis.get("evidence_chain");
        if (!(chain instanceof List<?> items) || items.isEmpty() || items.size() > 32) {
            errors.add("evidence_chain_missing_or_empty");
        } else {
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (!(item instanceof Map<?, ?> ref)) {
                    errors.add("evidence_chain[" + i + "]:invalid_object");
                    continue;
                }
                String id = text(ref.get("evidence_id"));
                String path = text(ref.get("field_path"));
                Object observed = ref.get("observed_value");
                if (!isReference(ref)) {
                    // Old clients can still inspect parsed text, but it is never marked as a
                    // verified model conclusion.
                    errors.add("evidence_chain[" + i + "]:structured_reference_required");
                    continue;
                }
                Object source = evidence.get(id);
                if (!(source instanceof Map<?, ?> sourceMap)) {
                    errors.add("unknown_evidence:" + id);
                    continue;
                }
                if (!(ref.get("evidence_version") instanceof String)
                        || !Objects.equals(
                                text(sourceMap.get("version")),
                                text(ref.get("evidence_version")))) {
                    errors.add("evidence_version_mismatch:" + id);
                }
                Object fields = sourceMap.get("fields");
                Object actual = lookup(fields, path);
                if (actual == null || observed == null || !equalValue(actual, observed)) {
                    errors.add("evidence_value_mismatch:" + id + ":" + path);
                }
                String unit =
                        path.endsWith("_minutes")
                                ? "minutes"
                                : path.endsWith("_seconds")
                                        ? "seconds"
                                        : path.endsWith("amount")
                                                        || path.endsWith("_balance")
                                                        || path.equals("authoritative_amount")
                                                ? "CNY"
                                                : "value";
                if (!unit.equals(text(ref.get("unit")))) {
                    errors.add("evidence_unit_mismatch:" + id + ":" + path);
                }
                if (!isText(ref.get("fact"))) {
                    errors.add("evidence_fact_required:" + id);
                }
            }
        }
        Object rules = analysis.get("applicable_rules");
        if (!(rules instanceof List<?> list) || list.size() > 32) {
            errors.add("applicable_rules_required_list");
        } else {
            for (Object rule : list) {
                if (!(rule instanceof String id) || !ruleIds.contains(id)) {
                    errors.add("rule_not_in_snapshot:" + rule);
                }
            }
        }
        return errors;
    }

    /** Both workflows use the same required, typed reference contract. */
    public static boolean isReference(Map<?, ?> ref) {
        Object observed = ref.get("observed_value");
        return isText(ref.get("fact"))
                && isIdentifier(ref.get("evidence_id"))
                && isIdentifier(ref.get("evidence_version"))
                && isIdentifier(ref.get("field_path"))
                && ref.containsKey("observed_value")
                && (observed instanceof Number || observed instanceof Boolean || isText(observed))
                && ref.get("unit") instanceof String unit
                && Set.of("value", "minutes", "seconds", "CNY").contains(unit);
    }

    private static Object lookup(Object source, String path) {
        Object current = source;
        for (String key : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static boolean equalValue(Object expected, Object observed) {
        if (expected instanceof Number) {
            if (!(observed instanceof Number)) {
                return false;
            }
            try {
                return new BigDecimal(expected.toString())
                                .compareTo(new BigDecimal(observed.toString()))
                        == 0;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return expected.getClass().equals(observed.getClass()) && expected.equals(observed);
    }

    private static String text(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private static boolean isText(Object raw) {
        return raw instanceof String value && !value.isBlank() && value.length() <= 2000;
    }

    private static boolean isIdentifier(Object raw) {
        return raw instanceof String value && !value.isBlank() && value.length() <= 200;
    }
}
