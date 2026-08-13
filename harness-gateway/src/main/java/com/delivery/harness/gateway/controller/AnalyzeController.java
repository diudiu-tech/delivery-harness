package com.delivery.harness.gateway.controller;

import com.delivery.harness.agent.orchestrator.AgentOrchestrator;
import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analyze")
@RequiredArgsConstructor
public class AnalyzeController {

    private final AgentOrchestrator orchestrator;

    @PostMapping("/abnormal-order")
    public ResponseEntity<HarnessResponse<WorkflowExecution>> analyzeAbnormalOrder(
            @Valid @RequestBody AbnormalOrderRequest request) {
        HarnessResponse<WorkflowExecution> response = orchestrator.process(AgentOrchestrator.AgentRequest.builder()
                .scenario(HarnessConstants.SCENARIO_ABNORMAL_ORDER)
                .input(Map.of("order_id", request.getOrderId()))
                .build());
        return toHttpResponse(response);
    }

    @PostMapping("/compensation")
    public ResponseEntity<HarnessResponse<WorkflowExecution>> suggestCompensation(
            @Valid @RequestBody CompensationRequest request) {
        Map<String, Object> input = new HashMap<>();
        input.put("order_id", request.getOrderId());
        input.put("complaint_type", request.getComplaintType());
        HarnessResponse<WorkflowExecution> response = orchestrator.process(AgentOrchestrator.AgentRequest.builder()
                .scenario(HarnessConstants.SCENARIO_COMPENSATION)
                .input(input)
                .build());
        return toHttpResponse(response);
    }

    private ResponseEntity<HarnessResponse<WorkflowExecution>> toHttpResponse(
            HarnessResponse<WorkflowExecution> response) {
        HttpStatus status = response.getCode() == 0 ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(response);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AbnormalOrderRequest {
        @NotBlank
        @JsonProperty("order_id")
        private String orderId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompensationRequest {
        @NotBlank
        @JsonProperty("order_id")
        private String orderId;

        @NotBlank
        @Pattern(regexp = "OVERTIME|WRONG_ORDER|DAMAGED|MISSING_ITEM")
        @JsonProperty("complaint_type")
        private String complaintType = "OVERTIME";
    }
}
