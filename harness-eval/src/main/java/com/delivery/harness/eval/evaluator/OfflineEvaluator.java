package com.delivery.harness.eval.evaluator;

import com.delivery.harness.agent.orchestrator.AgentOrchestrator;
import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.*;
import com.delivery.harness.eval.casemanager.EvalCaseManager;
import com.delivery.harness.eval.scorer.ExpertAlignmentScorer;
import com.delivery.harness.eval.scorer.RuleAccuracyScorer;
import com.delivery.harness.eval.scorer.ToolExecutionScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
                .status(HarnessConstants.STATUS_RUNNING)
                .startedAt(LocalDateTime.now())
                .build();
        runStore.put(runId, run);

        List<EvalResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            EvalResult result = evaluateCase(evalCase);
            result.setRunId(runId);
            results.add(result);
            run.setCompletedCases(run.getCompletedCases() + 1);
        }
        resultStore.put(runId, results);

        run.setStatus(HarnessConstants.STATUS_SUCCESS);
        run.setFinishedAt(LocalDateTime.now());
        log.info("Eval run completed: runId={}, cases={}", runId, cases.size());
        return run;
    }

    private EvalResult evaluateCase(EvalCase evalCase) {
        long startTime = System.currentTimeMillis();
        try {
            HarnessResponse<WorkflowExecution> response = orchestrator.process(
                    AgentOrchestrator.AgentRequest.builder()
                            .scenario(evalCase.getScenario())
                            .input(evalCase.getInput())
                            .build());

            Map<String, Object> actualOutput = response.getData() != null ?
                    response.getData().getOutput() : Collections.emptyMap();

            EvalResult.EvalScore score = EvalResult.EvalScore.builder()
                    .ruleAccuracy(ruleScorer.score(evalCase, actualOutput))
                    .expertAlignment(expertScorer.score(evalCase, actualOutput))
                    .toolExecutionAccuracy(toolScorer.score(evalCase, response.getData()))
                    .overallScore(0.0)
                    .build();
            score.setOverallScore((score.getRuleAccuracy() + score.getExpertAlignment()
                    + score.getToolExecutionAccuracy()) / 3.0);

            return EvalResult.builder()
                    .resultId(UUID.randomUUID().toString())
                    .caseId(evalCase.getCaseId())
                    .actualOutput(actualOutput)
                    .score(score)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("Eval case failed: caseId={}", evalCase.getCaseId(), e);
            return EvalResult.builder()
                    .resultId(UUID.randomUUID().toString())
                    .caseId(evalCase.getCaseId())
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    public Optional<EvalRun> getRun(String runId) {
        return Optional.ofNullable(runStore.get(runId));
    }

    public List<EvalResult> getResults(String runId) {
        return resultStore.getOrDefault(runId, Collections.emptyList());
    }
}
