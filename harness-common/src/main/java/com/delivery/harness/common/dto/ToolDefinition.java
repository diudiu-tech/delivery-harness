package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    private String toolName;
    private String description;
    private String category;
    private Map<String, ParameterDef> parameters;
    private String returnType;
    private Boolean enabled;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDef {
        private String name;
        private String type;
        private String description;
        private Boolean required;
        private List<String> enumValues;
    }
}
