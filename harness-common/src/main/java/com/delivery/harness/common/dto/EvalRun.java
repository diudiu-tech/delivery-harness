package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRun {

    private String runId;
    private String modelVersion;
    private String promptVersion;
    private List<String> caseIds;
    private String status;
    private Integer totalCases;
    private Integer completedCases;
    private Integer succeededCases;
    private Integer failedCases;
    /** Completed executions that retained results despite dependency or output-quality degradation. */
    private Integer degradedCases;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
