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
public class WorkflowTemplate {

    private String workflowId;
    private String name;
    private String scenario;
    private String description;
    private Integer version;
    private List<StepDef> steps;
    private Boolean enabled;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepDef {
        private Integer order;
        private String stepType;
        private String name;
        private Map<String, Object> config;
    }
}
