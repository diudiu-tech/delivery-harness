package com.delivery.harness.gateway.platform.execution;

import com.delivery.harness.gateway.platform.validation.PlatformRequest;
import com.delivery.harness.gateway.platform.validation.StrictLong;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ExecutionRequest(
        @JsonProperty("case_id")
                @NotBlank
                @Size(max = 160)
                @JsonDeserialize(using = StrictString.class)
                String caseId,
        @Pattern(regexp = "abnormal_order_analysis|compensation_suggestion")
                @JsonDeserialize(using = StrictString.class)
                String scenario,
        @JsonProperty("runtime_config_id")
                @Size(max = 160)
                @JsonDeserialize(using = StrictString.class)
                String runtimeConfigId,
        @JsonProperty("expected_case_version")
                @NotNull
                @Min(1)
                @JsonDeserialize(using = StrictLong.class)
                Long expectedCaseVersion,
        @JsonProperty("damage_level")
                @Pattern(regexp = "PARTIAL|FULL")
                @JsonDeserialize(using = StrictString.class)
                String damageLevel)
        implements PlatformRequest {}
