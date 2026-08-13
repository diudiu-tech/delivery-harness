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
public class CaseInfo {

    private String caseId;
    private String caseType;
    private String scenario;
    private String title;
    private String summary;
    private Map<String, Object> input;
    private Map<String, Object> expectedOutput;
    private Map<String, Object> actualOutput;
    private String expertAnswer;
    private List<String> tags;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
