package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.gateway.platform.validation.StrictBoolean;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Bounded typed HTTP requests for evaluation. */
public final class EvaluationRequests {
    private EvaluationRequests() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DatasetRequest(
            @Size(max = 256) @JsonDeserialize(using = StrictString.class) String name,
            @NotBlank
                    @Pattern(
                            regexp =
                                    "SYNTHETIC_SMOKE|BOUNDARY_REGRESSION|REAL_LABELLED|FROZEN_BLIND")
                    @JsonDeserialize(using = StrictString.class)
                    String split,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid SampleRequest> samples)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SampleRequest(
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String sampleId,
            @NotBlank
                    @Pattern(regexp = "abnormal_order_analysis|compensation_suggestion")
                    @JsonDeserialize(using = StrictString.class)
                    String scenario,
            @NotNull @Size(max = 20) @BoundedJson Map<String, Object> input,
            @BoundedJson(maxBytes = 120_000) OrderInfo evidenceOrder,
            @Size(max = 50) @BoundedJson Map<String, Object> expectedOutput,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> expectedRuleIds,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> expectedCauseCodes,
            @Size(max = 100) @BoundedJson @JsonAlias("expected_tools")
                    List<Map<String, Object>> expectedToolCalls,
            @Size(max = 100)
                    @JsonDeserialize(contentUsing = StrictString.class)
                    @JsonAlias("forbidden_tools")
                    List<@NotBlank @Size(max = 100) String> prohibitedToolCalls,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String sourceFeedbackId,
            @Size(max = 64) @JsonDeserialize(using = StrictString.class) String dataMode,
            @JsonDeserialize(using = StrictBoolean.class) Boolean damageEvidenceVerified)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EvalRunRequest(
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String datasetVersion,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String runtimeConfigId)
            implements QualityRequests.Request {}
}
