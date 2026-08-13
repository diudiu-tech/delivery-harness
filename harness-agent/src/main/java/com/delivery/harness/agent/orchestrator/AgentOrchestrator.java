package com.delivery.harness.agent.orchestrator;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.HarnessException;
import com.delivery.harness.common.util.TraceUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final WorkflowEngine workflowEngine;

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
            if (!"SUCCESS".equals(execution.getStatus())) {
                return HarnessResponse.error(500, "Workflow execution failed", execution, traceId);
            }
            return HarnessResponse.success(execution, traceId);
        } catch (HarnessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent processing failed: scenario={}, traceId={}", request.getScenario(), traceId, e);
            return HarnessResponse.error(500, "Agent processing failed", traceId);
        } finally {
            if (ownsTrace) {
                TraceUtil.clear();
            }
        }
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
