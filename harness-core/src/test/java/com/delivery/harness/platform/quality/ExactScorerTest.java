package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.dto.WorkflowExecution.StepExecution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ExactScorerTest {
    @Test
    void actualTypedToolLabelsAreMeasuredAndWrongArgumentsFailTheGate() {
        var execution =
                WorkflowExecution.builder()
                        .status("SUCCESS")
                        .output(Map.of())
                        .steps(
                                List.of(
                                        StepExecution.builder()
                                                .toolName("order_query")
                                                .status("SUCCESS")
                                                .input(Map.of("order_id", "ORDER-A"))
                                                .build()))
                        .build();
        var sample =
                Map.<String, Object>of(
                        "expected_tool_calls",
                        List.of(
                                Map.of(
                                        "name",
                                        "order_query",
                                        "parameters",
                                        Map.of("order_id", "ORDER-B"))));
        var report = ExactScorer.score(sample, execution);
        assertEquals(
                "MEASURED",
                QualityData.map(QualityData.map(report.get("measurements")).get("tools"))
                        .get("status"));
        assertFalse((Boolean) report.get("hard_gate_passed"));
        var matching =
                Map.<String, Object>of(
                        "expected_tool_calls",
                        List.of(
                                Map.of(
                                        "name",
                                        "order_query",
                                        "parameters",
                                        Map.of("order_id", "ORDER-A"))));
        assertTrue((Boolean) ExactScorer.score(matching, execution).get("hard_gate_passed"));
        assertFalse(
                (Boolean)
                        ExactScorer.score(
                                        Map.of("prohibited_tool_calls", List.of("order_query")),
                                        execution)
                                .get("hard_gate_passed"));
    }
}
