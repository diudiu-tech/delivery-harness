package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.validation.StrictBoolean;
import com.delivery.harness.gateway.platform.validation.StrictDecimal;
import com.delivery.harness.gateway.platform.validation.StrictInteger;
import com.delivery.harness.gateway.platform.validation.StrictLong;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Bounded typed HTTP requests for runtime. */
public final class RuntimeRequests {
    private RuntimeRequests() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuntimeRequest(
            @Size(max = 256) @JsonDeserialize(using = StrictString.class) String name,
            @NotBlank @Size(max = 200) @JsonDeserialize(using = StrictString.class) String model,
            @NotBlank @Size(max = 32_000) @JsonDeserialize(using = StrictString.class)
                    String prompt,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String policyVersion,
            @Pattern(regexp = "configured-default") @JsonDeserialize(using = StrictString.class)
                    String endpointRef,
            @Size(max = 50) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> knowledgeVersions,
            @DecimalMin("0") @DecimalMax("2") @JsonDeserialize(using = StrictDecimal.class)
                    BigDecimal temperature,
            @Min(1) @Max(8192) @JsonDeserialize(using = StrictInteger.class) Integer maxTokens,
            @Min(1) @Max(120) @JsonDeserialize(using = StrictInteger.class) Integer timeoutSeconds,
            @Min(0) @Max(2) @JsonDeserialize(using = StrictInteger.class) Integer maxRetries,
            @JsonDeserialize(using = StrictBoolean.class) Boolean deterministicOnly,
            @Pattern(regexp = "2\\.0") @JsonDeserialize(using = StrictString.class)
                    String schemaVersion,
            @Valid Pricing pricing)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pricing(
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class) String version,
            @NotBlank @Pattern(regexp = "CNY|USD") @JsonDeserialize(using = StrictString.class)
                    String currency,
            @NotNull
                    @DecimalMin("0")
                    @Digits(integer = 12, fraction = 8)
                    @JsonDeserialize(using = StrictDecimal.class)
                    BigDecimal inputPerMillion,
            @NotNull
                    @DecimalMin("0")
                    @Digits(integer = 12, fraction = 8)
                    @JsonDeserialize(using = StrictDecimal.class)
                    BigDecimal outputPerMillion) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuntimePublishRequest(
            @JsonDeserialize(using = StrictLong.class) @NotNull @Min(1) Long expectedVersion,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String evalRunId)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RouteRequest(
            @NotBlank
                    @Pattern(regexp = "abnormal_order_analysis|compensation_suggestion")
                    @JsonDeserialize(using = StrictString.class)
                    String scenario,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String primaryConfigId,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String fallbackConfigId,
            @NotNull @Min(0) @Max(100) @JsonDeserialize(using = StrictInteger.class)
                    Integer rolloutPercent,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> cityCodes,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> stationIds)
            implements QualityRequests.Request {}
}
