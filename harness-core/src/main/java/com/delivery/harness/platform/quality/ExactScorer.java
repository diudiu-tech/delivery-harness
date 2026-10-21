package com.delivery.harness.platform.quality;

import com.delivery.harness.common.dto.WorkflowExecution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Field-level labels and exact sets. Missing labels never become a perfect score. */
public final class ExactScorer {
    public static final String VERSION = "exact-fields-v1";

    private ExactScorer() {}

    public static Map<String, Object> score(
            Map<String, Object> sample, WorkflowExecution execution) {
        Map<String, Object> actual =
                execution == null || execution.getOutput() == null
                        ? Map.of()
                        : execution.getOutput();
        Map<String, Object> expected = QualityData.map(sample.get("expected_output"));
        Map<String, Object> measurements = new LinkedHashMap<>();
        List<String> hardFailures = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, Object> label : expected.entrySet()) {
            String field = label.getKey();
            Object observed = value(actual, field);
            boolean equal;
            try {
                if (Set.of("amount", "suggested_amount", "authoritative_amount").contains(field)) {
                    equal =
                            label.getValue() == null
                                    ? observed == null
                                    : cents(label.getValue()).equals(cents(observed));
                } else if (label.getValue() instanceof Boolean) {
                    equal = label.getValue().equals(observed);
                } else {
                    equal = Objects.equals(label.getValue(), observed);
                }
            } catch (RuntimeException e) {
                equal = false;
            }
            measurements.put(field, Map.of("status", "MEASURED", "pass", equal));
            measured++;
            if (!equal) {
                hardFailures.add("FIELD_MISMATCH:" + field);
            }
        }
        if (expected.isEmpty()) {
            measurements.put("fields", Map.of("status", "NOT_MEASURED"));
        }
        if (sample.containsKey("expected_rule_ids")) {
            Set<String> expectedIds =
                    new LinkedHashSet<>(QualityData.strings(sample.get("expected_rule_ids")));
            Set<String> actualIds = rules(actual);
            Set<String> intersection = new LinkedHashSet<>(expectedIds);
            intersection.retainAll(actualIds);
            double precision =
                    actualIds.isEmpty()
                            ? (expectedIds.isEmpty() ? 1 : 0)
                            : (double) intersection.size() / actualIds.size();
            double recall =
                    expectedIds.isEmpty() ? 1 : (double) intersection.size() / expectedIds.size();
            boolean exact = actualIds.equals(expectedIds);
            measurements.put(
                    "rules",
                    Map.of(
                            "status",
                            "MEASURED",
                            "precision",
                            precision,
                            "recall",
                            recall,
                            "exact_match",
                            exact,
                            "expected",
                            expectedIds,
                            "actual",
                            actualIds));
            measured++;
            if (!exact) {
                hardFailures.add("RULE_SET_MISMATCH");
            }
        } else {
            measurements.put("rules", Map.of("status", "NOT_MEASURED"));
        }
        if (sample.containsKey("expected_cause_codes")) {
            Set<String> allowed =
                    new LinkedHashSet<>(QualityData.strings(sample.get("expected_cause_codes")));
            String code = QualityData.text(actual, "primary_cause_code", "UNKNOWN");
            boolean correct = allowed.contains(code);
            measurements.put(
                    "cause", Map.of("status", "MEASURED", "exact_match", correct, "actual", code));
            measured++;
            if (!correct) {
                hardFailures.add("CAUSE_CODE_MISMATCH");
            }
        } else {
            measurements.put("cause", Map.of("status", "NOT_MEASURED"));
        }
        if (sample.containsKey("expected_tool_calls")
                || sample.containsKey("prohibited_tool_calls")
                || sample.containsKey("expected_tools")
                || sample.containsKey("forbidden_tools")) {
            boolean valid = toolsMatch(sample, execution);
            measurements.put("tools", Map.of("status", "MEASURED", "pass", valid));
            measured++;
            if (!valid) {
                hardFailures.add("TOOL_CONTRACT_MISMATCH");
            }
        } else {
            measurements.put("tools", Map.of("status", "NOT_MEASURED"));
        }
        if (execution == null
                || Set.of("FAILED", "TIMED_OUT", "CANCELLED").contains(execution.getStatus())) {
            hardFailures.add("EXECUTION_NOT_COMPLETED");
        }
        Object validationErrors = actual.get("validation_errors");
        if (validationErrors instanceof List<?> errors
                && errors.stream().anyMatch(e -> criticalEvidenceError(e.toString()))) {
            hardFailures.add("CRITICAL_EVIDENCE_VALIDATION");
        }
        if ("compensation_suggestion".equals(sample.get("scenario"))
                && (!expected.containsKey("currency")
                        || !expected.containsKey("decision_status")
                        || !(expected.containsKey("amount")
                                || expected.containsKey("authoritative_amount")))) {
            hardFailures.add("MISSING_CRITICAL_COMPENSATION_LABELS");
        }
        return Map.of(
                "scorer_version",
                VERSION,
                "measurements",
                measurements,
                "measured_dimensions",
                measured,
                "hard_failures",
                hardFailures,
                "hard_gate_passed",
                hardFailures.isEmpty() && measured > 0,
                "measurement_status",
                measured > 0 ? "MEASURED" : "NOT_MEASURED");
    }

    private static BigDecimal cents(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing amount");
        }
        if (value instanceof Map<?, ?> map) {
            value = map.get("value");
        }
        return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Object value(Map<String, Object> output, String field) {
        Object result = output.get(field);
        if (result != null || output.containsKey(field)) {
            return result;
        }
        return switch (field) {
            case "amount" ->
                    output.containsKey("authoritative_amount")
                            ? output.get("authoritative_amount")
                            : output.get("suggested_amount");
            case "method" -> output.get("suggested_method");
            default -> QualityData.map(output.get("decision")).get(field);
        };
    }

    private static boolean criticalEvidenceError(String error) {
        return error.contains("citation")
                || error.contains("invent")
                || error.startsWith("unknown_evidence:")
                || error.startsWith("evidence_version_mismatch:")
                || error.startsWith("evidence_value_mismatch:")
                || error.startsWith("evidence_unit_mismatch:")
                || error.startsWith("rule_not_in_snapshot:")
                || error.startsWith("unsupported_evidence")
                || error.contains(":invalid_object")
                || error.contains(":structured_reference_required");
    }

    static Set<String> rules(Map<String, Object> output) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> rule : PolicyService.ruleMaps(output.get("matched_rules"))) {
            if (rule.get("rule_id") != null) {
                ids.add(rule.get("rule_id").toString());
            }
        }
        ids.addAll(
                QualityData.strings(
                        QualityData.map(output.get("model_analysis")).get("applicable_rules")));
        return ids;
    }

    private static boolean toolsMatch(Map<String, Object> sample, WorkflowExecution execution) {
        if (execution == null || execution.getSteps() == null) {
            return false;
        }
        Set<String> forbidden =
                new LinkedHashSet<>(
                        QualityData.strings(
                                sample.getOrDefault(
                                        "prohibited_tool_calls", sample.get("forbidden_tools"))));
        if (execution.getSteps().stream().anyMatch(s -> forbidden.contains(s.getToolName()))) {
            return false;
        }
        for (Map<String, Object> expected :
                PolicyService.ruleMaps(
                        sample.getOrDefault("expected_tool_calls", sample.get("expected_tools")))) {
            String name = QualityData.text(expected, "name", "");
            Map<String, Object> params = QualityData.map(expected.get("parameters"));
            boolean found =
                    execution.getSteps().stream()
                            .anyMatch(
                                    step -> {
                                        if (!name.equals(step.getToolName())
                                                || !"SUCCESS".equals(step.getStatus())
                                                || step.getInput() == null) {
                                            return false;
                                        }
                                        return params.entrySet().stream()
                                                .allMatch(
                                                        entry ->
                                                                Objects.equals(
                                                                        entry.getValue(),
                                                                        step.getInput()
                                                                                .get(
                                                                                        entry
                                                                                                .getKey())));
                                    });
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
