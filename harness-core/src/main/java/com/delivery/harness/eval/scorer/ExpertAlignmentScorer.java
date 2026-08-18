package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.dto.EvalCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How closely the system's stated cause matches the expert's.
 *
 * <p>Prefers a direct comparison against {@code model_analysis.primary_cause}
 * — the field that actually states the system's conclusion — and only falls
 * back to keyword overlap when that field is absent.
 *
 * <p>The previous implementation scored keyword containment against
 * {@code actualOutput.toString()}, which is the entire response map: the
 * retrieved rule text, the citation titles and the echoed order all counted as
 * the model's own words. Common terms matched trivially and the score was
 * systematically inflated. It also returned {@code 1.0} for cases with no
 * expert answer, which rewarded incomplete cases.
 *
 * <p>This remains a crude lexical metric and is not a substitute for human
 * grading. It is calibrated to catch regressions, not to certify quality: a
 * score movement is a signal to look, not a verdict.
 */
@Slf4j
@Component
public class ExpertAlignmentScorer {

    private static final String TERM_SEPARATOR = "[,，；;、\\s]+";

    public OptionalDouble score(EvalCase evalCase, Map<String, Object> actualOutput) {
        String expertAnswer = evalCase.getExpertAnswer();
        if (expertAnswer == null || expertAnswer.isBlank()) {
            return OptionalDouble.empty();
        }
        if (actualOutput == null || actualOutput.isEmpty()) {
            return OptionalDouble.of(0d);
        }

        String primaryCause = primaryCause(actualOutput);
        if (primaryCause != null && !primaryCause.isBlank()) {
            boolean agrees = primaryCause.contains(expertAnswer) || expertAnswer.contains(primaryCause);
            if (agrees) {
                log.debug("ExpertAlignment: primary_cause '{}' matches expert '{}'", primaryCause, expertAnswer);
                return OptionalDouble.of(1d);
            }
        }

        // Fall back to term overlap against the model's own text only, never
        // against the retrieved evidence that was fed to it.
        String modelText = modelText(actualOutput);
        if (modelText.isBlank()) {
            return OptionalDouble.of(0d);
        }

        Set<String> terms = Arrays.stream(expertAnswer.toLowerCase().split(TERM_SEPARATOR))
                .map(String::trim)
                .filter(term -> term.length() > 1)
                .collect(Collectors.toSet());
        if (terms.isEmpty()) {
            return OptionalDouble.empty();
        }

        String haystack = modelText.toLowerCase();
        long matched = terms.stream().filter(haystack::contains).count();
        double score = (double) matched / terms.size();
        log.debug("ExpertAlignment: terms={}, matched={}, score={}", terms.size(), matched, score);
        return OptionalDouble.of(score);
    }

    private static String primaryCause(Map<String, Object> actualOutput) {
        Object analysis = actualOutput.get("model_analysis");
        if (analysis instanceof Map<?, ?> map && map.get("primary_cause") != null) {
            return map.get("primary_cause").toString().trim();
        }
        return null;
    }

    /** The model's own output, excluding retrieved evidence and echoed input. */
    private static String modelText(Map<String, Object> actualOutput) {
        Object raw = actualOutput.get("model_raw_output");
        return raw == null ? "" : raw.toString();
    }
}
