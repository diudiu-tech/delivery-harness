package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    private String toolName;
    private Boolean success;
    private Object data;
    private String errorMessage;
    private Long durationMs;
    private Map<String, Object> metadata;
}
