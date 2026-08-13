package com.delivery.harness.eval.replay;

import com.delivery.harness.agent.orchestrator.AgentOrchestrator;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.dto.WorkflowExecution;
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
public class ReplaySimulator {

    private final AgentOrchestrator orchestrator;

    public ReplayResult replay(ReplayRequest request) {
        log.info("Replay started: scenario={}, orderId={}", request.getScenario(), request.getInput().get("order_id"));
        long startTime = System.currentTimeMillis();

        HarnessResponse<WorkflowExecution> response = orchestrator.process(
                AgentOrchestrator.AgentRequest.builder()
                        .scenario(request.getScenario())
                        .input(request.getInput())
                        .build());

        return ReplayResult.builder()
                .execution(response.getData())
                .durationMs(System.currentTimeMillis() - startTime)
                .success(response.getCode() == 0)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplayRequest {
        private String scenario;
        private Map<String, Object> input;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplayResult {
        private WorkflowExecution execution;
        private Long durationMs;
        private Boolean success;
    }
}
