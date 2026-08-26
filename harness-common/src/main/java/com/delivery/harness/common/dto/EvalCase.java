package com.delivery.harness.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class EvalCase {

    @NotBlank
    @Size(max = 64)
    private String caseId;

    @NotBlank
    @Size(max = 64)
    private String scenario;

    @Size(max = 256)
    private String title;

    @Size(max = 10_000)
    private String description;

    @NotNull
    @Size(max = 50)
    private Map<String, Object> input;
    @Size(max = 50)
    private Map<String, Object> expectedOutput;
    @Size(max = 100)
    private List<String> expectedRuleIds;
    @Size(max = 100)
    private List<String> expectedToolCalls;
    @Size(max = 10_000)
    private String expertAnswer;
    @Size(max = 20)
    private List<String> tags;
    private LocalDateTime createdAt;
}
