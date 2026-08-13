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
public class EvalResult {

    private String resultId;
    private String runId;
    private String caseId;
    private Map<String, Object> actualOutput;
    private EvalScore score;
    private Long durationMs;
    private String errorMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvalScore {
        private Double ruleAccuracy;
        private Double expertAlignment;
        private Double toolExecutionAccuracy;
        private Double outputCompleteness;
        private Double hallucinationRate;
        private Double overallScore;
    }
}
