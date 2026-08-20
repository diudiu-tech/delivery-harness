package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.EvalCase;
import com.delivery.harness.common.dto.WorkflowExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the three scoring defects so they cannot come back.
 *
 * <p>Each scorer used to return 1.0 when its expectation was absent, and
 * RuleAccuracyScorer additionally counted a retrieved rule as an applied one
 * because it searched the whole response map, citations included.
 */
class ScorerTest {

    private final RuleAccuracyScorer ruleScorer = new RuleAccuracyScorer();
    private final ExpertAlignmentScorer expertScorer = new ExpertAlignmentScorer();
    private final ToolExecutionScorer toolScorer = new ToolExecutionScorer();

    // --- rule accuracy -----------------------------------------------------

    @Test
    void ruleAccuracyIsNotMeasuredWhenNoRulesAreExpected() {
        OptionalDouble score = ruleScorer.score(caseWithRules(null), analysisOutput("商家出餐慢", "ANA-001"));

        assertFalse(score.isPresent(), "an unlabelled case must not count as a perfect one");
    }

    @Test
    void ruleAccuracyCountsRulesTheModelApplied() {
        OptionalDouble score = ruleScorer.score(
                caseWithRules(List.of("ANA-001")), analysisOutput("商家出餐慢", "ANA-001"));

        assertEquals(1.0d, score.orElseThrow());
    }

    @Test
    void ruleAccuracyDoesNotCountARuleThatWasMerelyRetrieved() {
        // The rule appears in citations - it was fetched - but the model
        // applied a different one. Retrieval recall is not decision accuracy.
        Map<String, Object> output = analysisOutput("天气异常", "ANA-003");
        output.put("citations", List.of(citation("ANA-001"), citation("ANA-003")));

        OptionalDouble score = ruleScorer.score(caseWithRules(List.of("ANA-001")), output);

        assertEquals(0.0d, score.orElseThrow(),
                "a rule present only in citations must not score as applied");
    }

    @Test
    void ruleAccuracyReadsTheRuleEngineDecisionForCompensation() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("matched_rules", List.of(Map.of("rule_id", "COMP-001", "rule_name", "严重超时赔付")));

        assertEquals(1.0d, ruleScorer.score(caseWithRules(List.of("COMP-001")), output).orElseThrow());
    }

    @Test
    void ruleAccuracyIsPartialWhenOnlySomeRulesMatch() {
        OptionalDouble score = ruleScorer.score(
                caseWithRules(List.of("ANA-001", "ANA-002")), analysisOutput("商家出餐慢", "ANA-001"));

        assertEquals(0.5d, score.orElseThrow());
    }

    @Test
    void ruleAccuracyIsZeroWhenTheWorkflowProducedNothing() {
        assertEquals(0.0d,
                ruleScorer.score(caseWithRules(List.of("ANA-001")), Collections.emptyMap()).orElseThrow());
    }

    // --- expert alignment --------------------------------------------------

    @Test
    void expertAlignmentIsNotMeasuredWithoutAnExpertAnswer() {
        EvalCase evalCase = EvalCase.builder().caseId("C").scenario("s").input(Map.of()).build();

        assertFalse(expertScorer.score(evalCase, analysisOutput("商家出餐慢", "ANA-001")).isPresent());
    }

    @Test
    void expertAlignmentComparesTheStatedCauseFirst() {
        EvalCase evalCase = EvalCase.builder()
                .caseId("C").scenario("s").input(Map.of()).expertAnswer("商家出餐慢").build();

        assertEquals(1.0d, expertScorer.score(evalCase, analysisOutput("商家出餐慢", "ANA-001")).orElseThrow());
    }

    @Test
    void expertAlignmentScoresZeroForTheWrongCause() {
        EvalCase evalCase = EvalCase.builder()
                .caseId("C").scenario("s").input(Map.of()).expertAnswer("运力不足").build();

        // The raw output mentions a different cause entirely, so neither the
        // direct comparison nor the term fallback should find agreement.
        assertEquals(0.0d, expertScorer.score(evalCase, analysisOutput("天气异常", "ANA-003")).orElseThrow());
    }

    // --- tool execution ----------------------------------------------------

    @Test
    void toolExecutionIsNotMeasuredWhenNoToolsAreExpected() {
        assertFalse(toolScorer.score(caseWithTools(null), execution(step("order_query", true))).isPresent());
    }

    @Test
    void toolExecutionMatchesOnRegistryNameNotDisplayLabel() {
        WorkflowExecution execution = execution(step("order_query", true), step("eta_query", true));

        assertEquals(1.0d,
                toolScorer.score(caseWithTools(List.of("order_query", "eta_query")), execution).orElseThrow());
    }

    @Test
    void toolExecutionDoesNotCreditAFailedToolCall() {
        WorkflowExecution execution = execution(step("order_query", true), step("eta_query", false));

        assertEquals(0.5d,
                toolScorer.score(caseWithTools(List.of("order_query", "eta_query")), execution).orElseThrow());
    }

    @Test
    void toolExecutionIsZeroWithoutAnExecution() {
        assertEquals(0.0d, toolScorer.score(caseWithTools(List.of("order_query")), null).orElseThrow());
    }

    @Test
    void aFullyUnlabelledCaseHasNoOverallScoreAtAll() {
        EvalCase bare = EvalCase.builder().caseId("C").scenario("s").input(Map.of()).build();
        Map<String, Object> output = analysisOutput("商家出餐慢", "ANA-001");

        boolean anyMeasured = ruleScorer.score(bare, output).isPresent()
                || expertScorer.score(bare, output).isPresent()
                || toolScorer.score(bare, execution(step("order_query", true))).isPresent();

        assertFalse(anyMeasured, "an empty case used to record a perfect 1.0 overall");
    }

    // --- helpers -----------------------------------------------------------

    private static EvalCase caseWithRules(List<String> ruleIds) {
        return EvalCase.builder()
                .caseId("C").scenario("s").input(Map.of()).expectedRuleIds(ruleIds).build();
    }

    private static EvalCase caseWithTools(List<String> tools) {
        return EvalCase.builder()
                .caseId("C").scenario("s").input(Map.of()).expectedToolCalls(tools).build();
    }

    private static Map<String, Object> analysisOutput(String primaryCause, String appliedRuleId) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("primary_cause", primaryCause);
        analysis.put("applicable_rules", List.of(appliedRuleId));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("model_analysis", analysis);
        output.put("model_raw_output", "{\"primary_cause\":\"" + primaryCause + "\"}");
        return output;
    }

    private static Map<String, Object> citation(String sourceId) {
        return Map.of("source_type", "rule", "source_id", sourceId, "title", sourceId);
    }

    private static WorkflowExecution.StepExecution step(String toolName, boolean success) {
        return WorkflowExecution.StepExecution.builder()
                .stepType(HarnessConstants.STEP_TOOL_CALL)
                .name("显示用名称")
                .toolName(toolName)
                .status(success ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED)
                .durationMs(1L)
                .build();
    }

    private static WorkflowExecution execution(WorkflowExecution.StepExecution... steps) {
        List<WorkflowExecution.StepExecution> stepList = new ArrayList<>(List.of(steps));
        return WorkflowExecution.builder().steps(stepList).build();
    }
}
