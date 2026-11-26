package com.delivery.harness.agent.workflow;

import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.HarnessException;
import com.delivery.harness.common.exception.RuntimeBoundaryException;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.runtime.RuntimeBinding;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WorkflowEngine {

    private final ThreadLocal<WorkflowExecution> failedExecution = new ThreadLocal<>();

    public WorkflowExecution takeFailedExecution() {
        WorkflowExecution failed = failedExecution.get();
        failedExecution.remove();
        return failed;
    }

    private final Map<String, WorkflowHandler> handlers = new ConcurrentHashMap<>();

    public void registerHandler(String scenario, WorkflowHandler handler) {
        handlers.put(scenario, handler);
        log.info("Workflow handler registered: scenario={}", scenario);
    }

    public WorkflowExecution execute(String scenario, Map<String, Object> input) {
        return execute(scenario, input, false);
    }

    /**
     * Server-only v2 entry point: a missing binding must not select mutable or synthetic v1
     * sources.
     */
    public WorkflowExecution executeTrusted(String scenario, Map<String, Object> input) {
        return execute(scenario, input, true);
    }

    private WorkflowExecution execute(String scenario, Map<String, Object> input, boolean trusted) {
        failedExecution.remove();
        WorkflowHandler handler = handlers.get(scenario);
        if (handler == null) {
            throw new IllegalArgumentException("No workflow handler for scenario: " + scenario);
        }

        String executionId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        WorkflowExecution execution =
                WorkflowExecution.builder()
                        .executionId(executionId)
                        .traceId(TraceUtil.getTraceId())
                        .scenario(scenario)
                        .input(input)
                        .status(HarnessConstants.STATUS_RUNNING)
                        .steps(new ArrayList<>())
                        .startedAt(LocalDateTime.now())
                        .build();

        try {
            if (trusted) {
                RuntimeBinding.requireWorkflowContext();
            }
            Map<String, Object> output = handler.handle(input, execution);
            execution.setOutput(output);
            boolean degraded =
                    (output != null
                                    && Boolean.TRUE.equals(
                                            output.get(HarnessConstants.OUTPUT_DEGRADED)))
                            || execution.getSteps().stream()
                                    .anyMatch(
                                            s ->
                                                    HarnessConstants.STATUS_FAILED.equals(
                                                            s.getStatus()));
            execution.setStatus(
                    degraded ? HarnessConstants.STATUS_DEGRADED : HarnessConstants.STATUS_SUCCESS);
        } catch (HarnessException e) {
            log.error(
                    "Workflow dependency failed: scenario={}, executionId={}",
                    scenario,
                    executionId,
                    e);
            execution.setStatus(HarnessConstants.STATUS_FAILED);
            ensureFailureStep(execution, e);
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("error", "Required workflow dependency failed");
            failure.put(
                    "error_code",
                    e instanceof RuntimeBoundaryException boundary
                            ? boundary.getReasonCode()
                            : e.getCode());
            failure.put("error_type", e.getClass().getSimpleName());
            failure.put("retryable", !(e instanceof RuntimeBoundaryException));
            execution.setOutput(failure);
            execution.setFinishedAt(LocalDateTime.now());
            execution.setTotalDurationMs(System.currentTimeMillis() - startTime);
            failedExecution.set(execution);
            throw e;
        } catch (Exception e) {
            execution.setStatus(HarnessConstants.STATUS_FAILED);
            ensureFailureStep(execution, e);
            execution.setOutput(
                    Collections.<String, Object>singletonMap("error", "Workflow execution failed"));
            log.error(
                    "Workflow execution failed: scenario={}, executionId={}",
                    scenario,
                    executionId,
                    e);
        }

        execution.setFinishedAt(LocalDateTime.now());
        execution.setTotalDurationMs(System.currentTimeMillis() - startTime);
        return execution;
    }

    private static void ensureFailureStep(WorkflowExecution execution, Exception failure) {
        if (execution.getSteps().isEmpty()
                || !HarnessConstants.STATUS_FAILED.equals(
                        execution.getSteps().get(execution.getSteps().size() - 1).getStatus())) {
            new StepRecorder(execution)
                    .record(
                            "execution_failure",
                            "必需执行步骤失败",
                            null,
                            HarnessConstants.STATUS_FAILED,
                            Collections.emptyMap(),
                            Map.of(
                                    "required",
                                    true,
                                    "error_type",
                                    failure.getClass().getSimpleName()),
                            0,
                            "Workflow dependency failed");
        }
    }

    @FunctionalInterface
    public interface WorkflowHandler {
        Map<String, Object> handle(Map<String, Object> input, WorkflowExecution execution);
    }
}
