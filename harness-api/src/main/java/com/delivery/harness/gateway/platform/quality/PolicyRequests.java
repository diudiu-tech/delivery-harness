package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.validation.StrictBoolean;
import com.delivery.harness.gateway.platform.validation.StrictInstant;
import com.delivery.harness.gateway.platform.validation.StrictInteger;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bounded typed HTTP requests for policy. */
public final class PolicyRequests {
    private PolicyRequests() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PolicyRequest(
            @Size(max = 256) @JsonDeserialize(using = StrictString.class) String name,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid RuleRequest> rules,
            @Pattern(regexp = "CNY") @JsonDeserialize(using = StrictString.class) String currency,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> cityCodes,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> stationIds,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveFrom,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveTo)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleRequest(
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class) String ruleId,
            @NotBlank @Size(max = 256) @JsonDeserialize(using = StrictString.class) String ruleName,
            @NotBlank
                    @Pattern(regexp = "compensation|analysis")
                    @JsonDeserialize(using = StrictString.class)
                    String ruleType,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String category,
            @Size(max = 10_000) @JsonDeserialize(using = StrictString.class) String content,
            @Min(0) @Max(10_000) @JsonDeserialize(using = StrictInteger.class) Integer priority,
            @JsonDeserialize(using = StrictBoolean.class) Boolean enabled,
            @Size(max = 30) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> tags,
            @NotNull @Size(max = 20) @BoundedJson Map<String, Object> conditions,
            @NotNull @Size(max = 20) @BoundedJson Map<String, Object> actions,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String version,
            @Size(max = 32) @JsonDeserialize(using = StrictString.class) String lifecycle,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveFrom,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveTo,
            @Pattern(regexp = "CNY") @JsonDeserialize(using = StrictString.class) String currency)
            implements QualityRequests.Request {}
}
