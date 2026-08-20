package com.delivery.harness.llm.gateway;

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
public class LlmChatRequest {

    private String scenario;
    private List<LlmMessage> messages;
    private List<Map<String, Object>> tools;
    private Double temperature;
    private Integer maxTokens;
}
