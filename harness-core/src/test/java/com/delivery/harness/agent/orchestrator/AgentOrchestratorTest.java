package com.delivery.harness.agent.orchestrator;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentOrchestratorTest {

    private final WorkflowEngine workflowEngine = new WorkflowEngine();
    private final TraceService traceService = new TraceService(10);
    private final MetricsService metricsService = new MetricsService();
    private final AgentOrchestrator orchestrator =
            new AgentOrchestrator(workflowEngine, traceService, metricsService);

    @AfterEach
    void clearTrace() {
        TraceUtil.clear();
    }

    @Test
    void recordsSuccessfulExecution() {
        workflowEngine.registerHandler("success", (input, execution) -> Map.of("ok", true));
        TraceUtil.setTraceId("success-trace");

        HarnessResponse<WorkflowExecution> response = orchestrator.process(request("success"));

        assertEquals(0, response.getCode());
        assertEquals("SUCCESS", traceService.getTrace("success-trace").orElseThrow().getStatus());
    }

    @Test
    void recordsDependencyFailureBeforeRethrowing() {
        workflowEngine.registerHandler("dependency-failure", (input, execution) -> {
            throw new LlmException("test-model", "unavailable");
        });
        TraceUtil.setTraceId("dependency-trace");

        assertThrows(LlmException.class, () -> orchestrator.process(request("dependency-failure")));

        assertEquals("FAILED", traceService.getTrace("dependency-trace").orElseThrow().getStatus());
    }

    @Test
    void convertsUnexpectedFailureToErrorResponseAndRecordsIt() {
        workflowEngine.registerHandler("unexpected-failure", (input, execution) -> {
            throw new IllegalStateException("boom");
        });
        TraceUtil.setTraceId("unexpected-trace");

        HarnessResponse<WorkflowExecution> response = orchestrator.process(request("unexpected-failure"));

        assertEquals(500, response.getCode());
        assertEquals("FAILED", traceService.getTrace("unexpected-trace").orElseThrow().getStatus());
    }

    private static AgentOrchestrator.AgentRequest request(String scenario) {
        return AgentOrchestrator.AgentRequest.builder()
                .scenario(scenario)
                .input(Map.of("test", true))
                .build();
    }
}
