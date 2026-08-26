package com.delivery.harness.eval.evaluator;

import com.delivery.harness.agent.orchestrator.AgentOrchestrator;
import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.EvalCase;
import com.delivery.harness.common.dto.EvalResult;
import com.delivery.harness.common.dto.EvalRun;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.eval.casemanager.EvalCaseManager;
import com.delivery.harness.eval.scorer.ExpertAlignmentScorer;
import com.delivery.harness.eval.scorer.RuleAccuracyScorer;
import com.delivery.harness.eval.scorer.ToolExecutionScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs evaluation cases synchronously and scores the results.
 *
 * <p>Scores are nullable by design. A scorer returns an empty result when the
 * case declared no expectation for it, and only present scores contribute to
 * {@code overallScore}. Previously each scorer returned {@code 1.0} in that
 * situation, so a case with no expectations recorded a perfect result and the
 * suite average rose as cases were added without labels.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineEvaluator {

    private final EvalCaseManager caseManager;
    private final AgentOrchestrator orchestrator;
    private final RuleAccuracyScorer ruleScorer;
    private final ExpertAlignmentScorer expertScorer;
    private final ToolExecutionScorer toolScorer;

    private final Map<String, EvalRun> runStore = new ConcurrentHashMap<>();
    private final Map<String, List<EvalResult>> resultStore = new ConcurrentHashMap<>();

    public EvalRun startRun(List<String> caseIds, String modelVersion, String promptVersion) {
        String runId = UUID.randomUUID().toString();
        List<EvalCase> cases = caseManager.findByIds(caseIds);

        EvalRun run = EvalRun.builder()
                .runId(runId)
                .modelVersion(modelVersion)
                .promptVersion(promptVersion)
                .caseIds(caseIds)
                .totalCases(cases.size())
                .completedCases(0)
                .succeededCases(0)
                .failedCases(0)
                .status(HarnessConstants.STATUS_RUNNING)
                .startedAt(LocalDateTime.now())
                .build();
        runStore.put(runId, run);

        List<EvalResult> results = new ArrayList<>();
        String parentTraceId = TraceUtil.getTraceId();
        for (EvalCase evalCase : cases) {
            TraceUtil.setTraceId(TraceUtil.generateTraceId());
            try {
                EvalResult result = evaluateCase(evalCase);
                result.setRunId(runId);
                results.add(result);
                run.setCompletedCases(run.getCompletedCases() + 1);
                if (result.getErrorMessage() == null || result.getErrorMessage().isBlank()) {
                    run.setSucceededCases(run.getSucceededCases() + 1);
                } else {
                    run.setFailedCases(run.getFailedCases() + 1);
                }
            } finally {
                if (parentTraceId == null) {
                    TraceUtil.clear();
                } else {
                    TraceUtil.setTraceId(parentTraceId);
                }
            }
        }
        resultStore.put(runId, results);

        run.setStatus(statusFor(run));
        run.setFinishedAt(LocalDateTime.now());
        log.info("Eval run completed: runId={}, cases={}", runId, cases.size());
        return run;
    }

    private EvalResult evaluateCase(EvalCase evalCase) {
        long startTime = System.nanoTime();
        try {
            HarnessResponse<WorkflowExecution> response = orchestrator.process(
                    AgentOrchestrator.AgentRequest.builder()
                            .scenario(evalCase.getScenario())
                            .input(evalCase.getInput())
                            .build());

            WorkflowExecution execution = response.getData();
            String traceId = execution == null ? TraceUtil.getTraceId() : execution.getTraceId();
            Map<String, Object> actualOutput = execution != null && execution.getOutput() != null
                    ? execution.getOutput()
                    : Collections.emptyMap();

            if (response.getCode() != 0 || execution == null
                    || !HarnessConstants.STATUS_SUCCESS.equals(execution.getStatus())) {
                String errorMessage = response.getMessage();
                if (errorMessage == null || errorMessage.isBlank()) {
                    errorMessage = "Workflow returned code " + response.getCode();
                }
                return EvalResult.builder()
                        .resultId(UUID.randomUUID().toString())
                        .caseId(evalCase.getCaseId())
                        .traceId(traceId)
                        .actualOutput(actualOutput)
                        .errorMessage(errorMessage)
                        .durationMs(elapsedMs(startTime))
                        .build();
            }

            OptionalDouble ruleAccuracy = ruleScorer.score(evalCase, actualOutput);
            OptionalDouble expertAlignment = expertScorer.score(evalCase, actualOutput);
            OptionalDouble toolExecution = toolScorer.score(evalCase, execution);

            EvalResult.EvalScore score = EvalResult.EvalScore.builder()
                    .ruleAccuracy(boxed(ruleAccuracy))
                    .expertAlignment(boxed(expertAlignment))
                    .toolExecutionAccuracy(boxed(toolExecution))
                    .overallScore(mean(ruleAccuracy, expertAlignment, toolExecution))
                    .build();

            return EvalResult.builder()
                    .resultId(UUID.randomUUID().toString())
                    .caseId(evalCase.getCaseId())
                    .traceId(traceId)
                    .actualOutput(actualOutput)
                    .score(score)
                    .durationMs(elapsedMs(startTime))
                    .build();

        } catch (Exception e) {
            log.error("Eval case failed: caseId={}", evalCase.getCaseId(), e);
            return EvalResult.builder()
                    .resultId(UUID.randomUUID().toString())
                    .caseId(evalCase.getCaseId())
                    .traceId(TraceUtil.getTraceId())
                    .errorMessage("Evaluation case failed: " + e.getClass().getSimpleName())
                    .durationMs(elapsedMs(startTime))
                    .build();
        }
    }

    /**
     * Mean of the scores that were actually measured. Null when a case
     * declared no expectations at all — reporting no score is honest, whereas
     * reporting 1.0 or 0.0 would both be inventions.
     */
    static Double mean(OptionalDouble... scores) {
        double sum = 0d;
        int count = 0;
        for (OptionalDouble score : scores) {
            if (score.isPresent()) {
                sum += score.getAsDouble();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private static Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    public Optional<EvalRun> getRun(String runId) {
        return Optional.ofNullable(runStore.get(runId));
    }

    public List<EvalResult> getResults(String runId) {
        return resultStore.getOrDefault(runId, Collections.emptyList());
    }

    private static String statusFor(EvalRun run) {
        if (run.getTotalCases() == 0 || run.getFailedCases() != null && run.getFailedCases() == run.getTotalCases()) {
            return HarnessConstants.STATUS_FAILED;
        }
        if (run.getFailedCases() != null && run.getFailedCases() > 0) {
            return HarnessConstants.STATUS_PARTIAL_SUCCESS;
        }
        return HarnessConstants.STATUS_SUCCESS;
    }
}
