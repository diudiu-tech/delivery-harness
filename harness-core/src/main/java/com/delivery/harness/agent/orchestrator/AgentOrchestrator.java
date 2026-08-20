package com.delivery.harness.agent.orchestrator;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.HarnessException;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point for a scenario request: runs the workflow, then records what
 * happened.
 *
 * <p>The recording is the new part. {@code TraceService.recordTrace} and
 * {@code MetricsService} previously had no callers anywhere, so the
 * {@code /api/v1/observe} endpoints always returned empty while advertising
 * tracing and metrics. Wiring them costs about thirty lines and is cheaper
 * than deleting three public endpoints — see ADR-0001 for why these two
 * components were kept when other unused code was removed.
 *
 * <p>Recording never fails a request. A workflow that succeeded but whose
 * trace could not be stored is still a success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final WorkflowEngine workflowEngine;
    private final TraceService traceService;
    private final MetricsService metricsService;

    public HarnessResponse<WorkflowExecution> process(AgentRequest request) {
        String traceId = TraceUtil.getTraceId();
        boolean ownsTrace = traceId == null;
        if (ownsTrace) {
            traceId = TraceUtil.generateTraceId();
            TraceUtil.setTraceId(traceId);
        }

        try {
            log.info("Agent processing: scenario={}, traceId={}", request.getScenario(), traceId);
            WorkflowExecution execution = workflowEngine.execute(request.getScenario(), request.getInput());
            observe(traceId, request, execution);

            if (!HarnessConstants.STATUS_SUCCESS.equals(execution.getStatus())) {
                return HarnessResponse.error(500, "Workflow execution failed", execution, traceId);
            }
            return HarnessResponse.success(execution, traceId);
        } catch (HarnessException e) {
            metricsService.incrementCounter(metricName(request.getScenario(), "dependency_failure"));
            throw e;
        } catch (Exception e) {
            log.error("Agent processing failed: scenario={}, traceId={}", request.getScenario(), traceId, e);
            metricsService.incrementCounter(metricName(request.getScenario(), "error"));
            return HarnessResponse.error(500, "Agent processing failed", traceId);
        } finally {
            if (ownsTrace) {
                TraceUtil.clear();
            }
        }
    }

    /**
     * Stores one trace and updates counters. Wrapped so an observability
     * failure can never turn a successful workflow into a failed request.
     */
    private void observe(String traceId, AgentRequest request, WorkflowExecution execution) {
        try {
            traceService.recordTrace(TraceService.TraceRecord.builder()
                    .traceId(traceId)
                    .scenario(request.getScenario())
                    .input(request.getInput())
                    .output(execution.getOutput())
                    .spans(toSpans(execution))
                    .totalDurationMs(execution.getTotalDurationMs())
                    .status(execution.getStatus())
                    .createdAt(LocalDateTime.now())
                    .build());

            String outcome = HarnessConstants.STATUS_SUCCESS.equals(execution.getStatus())
                    ? "success" : "failure";
            metricsService.incrementCounter(metricName(request.getScenario(), outcome));
            if (execution.getTotalDurationMs() != null) {
                metricsService.recordLatency(
                        metricName(request.getScenario(), "latency"), execution.getTotalDurationMs());
            }
            recordStepLatencies(request.getScenario(), execution);
        } catch (RuntimeException e) {
            log.warn("Failed to record trace or metrics for traceId={}: {}", traceId, e.getMessage());
        }
    }

    private void recordStepLatencies(String scenario, WorkflowExecution execution) {
        if (execution.getSteps() == null) {
            return;
        }
        for (WorkflowExecution.StepExecution step : execution.getSteps()) {
            if (step.getDurationMs() == null || step.getStepType() == null) {
                continue;
            }
            metricsService.recordLatency(
                    metricName(scenario, step.getStepType() + "_latency"), step.getDurationMs());
        }
    }

    private static List<TraceService.TraceSpan> toSpans(WorkflowExecution execution) {
        if (execution.getSteps() == null) {
            return Collections.emptyList();
        }
        return execution.getSteps().stream()
                .map(step -> TraceService.TraceSpan.builder()
                        .spanId(UUID.randomUUID().toString())
                        .spanType(step.getStepType())
                        .name(step.getName())
                        .durationMs(step.getDurationMs())
                        .attributes(Map.of(
                                "status", String.valueOf(step.getStatus()),
                                "tool_name", String.valueOf(step.getToolName())))
                        .build())
                .collect(Collectors.toList());
    }

    private static String metricName(String scenario, String suffix) {
        return (scenario == null ? "unknown" : scenario) + "_" + suffix;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentRequest {
        private String scenario;
        private Map<String, Object> input;
    }
}
