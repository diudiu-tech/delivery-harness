package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.EvalCase;
import com.delivery.harness.common.dto.WorkflowExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Fraction of the expected tools the execution actually invoked.
 *
 * <p>Matches on {@link WorkflowExecution.StepExecution#getToolName()}, the
 * registry name. The previous implementation matched on the step's display
 * label — a Chinese phrase such as "查询订单详情" — so renaming a label
 * silently broke every expectation, and a case had to encode presentation
 * text to express a behavioural requirement.
 *
 * <p>Only steps that both are {@code tool_call} and succeeded count. A tool
 * that was invoked and failed did not supply the evidence the case expects.
 *
 * <p>Absent expectations return an empty result rather than {@code 1.0}, so
 * an unspecified case no longer scores as a perfect one.
 */
@Slf4j
@Component
public class ToolExecutionScorer {

    public OptionalDouble score(EvalCase evalCase, WorkflowExecution execution) {
        List<String> expected = evalCase.getExpectedToolCalls();
        if (expected == null || expected.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (execution == null || execution.getSteps() == null) {
            return OptionalDouble.of(0d);
        }

        Set<String> invoked = execution.getSteps().stream()
                .filter(step -> HarnessConstants.STEP_TOOL_CALL.equals(step.getStepType()))
                .filter(step -> HarnessConstants.STATUS_SUCCESS.equals(step.getStatus()))
                .map(WorkflowExecution.StepExecution::getToolName)
                .filter(name -> name != null && !name.isBlank())
                .map(ToolExecutionScorer::normalise)
                .collect(Collectors.toSet());

        long matched = expected.stream()
                .filter(tool -> tool != null && invoked.contains(normalise(tool)))
                .count();

        double score = (double) matched / expected.size();
        log.debug("ToolExecution: expected={}, invoked={}, matched={}, score={}",
                expected, invoked, matched, score);
        return OptionalDouble.of(score);
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
