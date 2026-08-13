package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocation {

    private String invocationId;
    private String traceId;
    private String toolName;
    private Map<String, Object> parameters;
    private LocalDateTime invokedAt;
    private Long durationMs;
    private Boolean success;
    private String errorMessage;
}
