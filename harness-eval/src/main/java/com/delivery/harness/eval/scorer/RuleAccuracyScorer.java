package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.dto.EvalCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Fraction of the expected rule IDs the system actually decided on.
 *
 * <p>Two things were wrong with the previous implementation.
 *
 * <p>It scored {@code actualOutput.toString().contains(ruleId)} over the whole
 * response — including the {@code citations} block, whose entries carry a
 * {@code source_id} equal to the rule ID. Merely retrieving a rule therefore
 * counted as having applied it. The metric measured retrieval recall while
 * being reported as decision accuracy, and a system that retrieved everything
 * and reasoned about nothing would have scored perfectly.
 *
 * <p>It also returned {@code 1.0} when a case declared no expected rules, so
 * an unspecified case was indistinguishable from a perfectly answered one.
 * Absent expectations now yield an empty result and are excluded from the
 * overall score rather than inflating it.
 *
 * <p>Rule IDs are read only from fields that represent a decision:
 * {@code model_analysis.applicable_rules} (what the model claimed applies) and
 * {@code matched_rules[].rule_id} (what the rule engine determined). Citations
 * are deliberately not consulted.
 */
@Slf4j
@Component
public class RuleAccuracyScorer {

    public OptionalDouble score(EvalCase evalCase, Map<String, Object> actualOutput) {
        List<String> expected = evalCase.getExpectedRuleIds();
        if (expected == null || expected.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (actualOutput == null || actualOutput.isEmpty()) {
            return OptionalDouble.of(0d);
        }

        Set<String> decided = decidedRuleIds(actualOutput);
        long matched = expected.stream()
                .filter(ruleId -> ruleId != null && decided.contains(normalise(ruleId)))
                .count();

        double score = (double) matched / expected.size();
        log.debug("RuleAccuracy: expected={}, decided={}, matched={}, score={}",
                expected, decided, matched, score);
        return OptionalDouble.of(score);
    }

    /** Rule IDs the system committed to, as opposed to merely retrieved. */
    static Set<String> decidedRuleIds(Map<String, Object> actualOutput) {
        Set<String> ids = new LinkedHashSet<>();

        // Attribution: the model's own claim about which rules apply.
        Object analysis = actualOutput.get("model_analysis");
        if (analysis instanceof Map<?, ?> analysisMap) {
            for (String id : asStringList(analysisMap.get("applicable_rules"))) {
                ids.add(normalise(id));
            }
        }

        // Compensation: the rule engine's determination.
        Object matchedRules = actualOutput.get("matched_rules");
        if (matchedRules instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (entry instanceof Map<?, ?> ruleMap && ruleMap.get("rule_id") != null) {
                    ids.add(normalise(ruleMap.get("rule_id").toString()));
                } else if (entry instanceof String text) {
                    ids.add(normalise(text));
                }
            }
        }

        ids.remove("");
        return ids;
    }

    private static List<String> asStringList(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    values.add(item.toString());
                }
            }
        } else if (value instanceof String text) {
            values.add(text);
        }
        return values;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
