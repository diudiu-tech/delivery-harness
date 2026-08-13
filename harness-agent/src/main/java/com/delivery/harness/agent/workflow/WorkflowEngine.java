package com.delivery.harness.agent.workflow;

import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.HarnessException;
import com.delivery.harness.common.util.TraceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WorkflowEngine {

    private final Map<String, WorkflowHandler> handlers = new ConcurrentHashMap<>();

    public void registerHandler(String scenario, WorkflowHandler handler) {
        handlers.put(scenario, handler);
        log.info("Workflow handler registered: scenario={}", scenario);
    }

    public WorkflowExecution execute(String scenario, Map<String, Object> input) {
        WorkflowHandler handler = handlers.get(scenario);
        if (handler == null) {
            throw new IllegalArgumentException("No workflow handler for scenario: " + scenario);
        }

        String executionId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId(executionId)
                .traceId(TraceUtil.getTraceId())
                .scenario(scenario)
                .input(input)
                .status(HarnessConstants.STATUS_RUNNING)
                .steps(new ArrayList<>())
                .startedAt(LocalDateTime.now())
                .build();

        try {
            Map<String, Object> output = handler.handle(input, execution);
            execution.setOutput(output);
            execution.setStatus(HarnessConstants.STATUS_SUCCESS);
        } catch (HarnessException e) {
            log.error("Workflow dependency failed: scenario={}, executionId={}", scenario, executionId, e);
            throw e;
        } catch (Exception e) {
            execution.setStatus(HarnessConstants.STATUS_FAILED);
            execution.setOutput(Collections.<String, Object>singletonMap("error", "Workflow execution failed"));
            log.error("Workflow execution failed: scenario={}, executionId={}", scenario, executionId, e);
        }

        execution.setFinishedAt(LocalDateTime.now());
        execution.setTotalDurationMs(System.currentTimeMillis() - startTime);
        return execution;
    }

    @FunctionalInterface
    public interface WorkflowHandler {
        Map<String, Object> handle(Map<String, Object> input, WorkflowExecution execution);
    }
}
