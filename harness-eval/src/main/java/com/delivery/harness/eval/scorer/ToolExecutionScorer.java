package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.dto.EvalCase;
import com.delivery.harness.common.dto.WorkflowExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolExecutionScorer {

    public double score(EvalCase evalCase, WorkflowExecution execution) {
        List<String> expectedTools = evalCase.getExpectedToolCalls();
        if (expectedTools == null || expectedTools.isEmpty()) {
            return 1.0;
        }
        if (execution == null || execution.getSteps() == null) {
            return 0.0;
        }

        List<String> actualTools = execution.getSteps().stream()
                .filter(s -> "tool_call".equals(s.getStepType()))
                .map(WorkflowExecution.StepExecution::getName)
                .collect(Collectors.toList());

        long matched = expectedTools.stream()
                .filter(t -> actualTools.stream().anyMatch(a -> a.toLowerCase().contains(t.toLowerCase())))
                .count();

        double score = (double) matched / expectedTools.size();
        log.debug("ToolExecution: expected={}, matched={}, score={}", expectedTools.size(), matched, score);
        return score;
    }
}
