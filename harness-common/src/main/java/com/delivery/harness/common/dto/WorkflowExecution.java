package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowExecution {

    private String executionId;
    private String workflowId;
    private String traceId;
    private String scenario;
    private Map<String, Object> input;
    private Map<String, Object> output;
    private String status;
    private List<StepExecution> steps;
    private Long totalDurationMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepExecution {
        private Integer order;
        private String stepType;
        private String name;

        /**
         * Registry name of the invoked tool for {@code tool_call} steps, null
         * otherwise. {@code name} is a human-readable label and is not a
         * stable identifier, so evaluation and metrics must key on this field.
         */
        private String toolName;

        private String status;
        private Map<String, Object> input;
        private Map<String, Object> output;
        private Long durationMs;
        private String errorMessage;
    }
}
