package com.delivery.harness.llm.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatResponse {

    private String model;
    private String content;
    private Boolean success;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long durationMs;
    private String errorMessage;
}
