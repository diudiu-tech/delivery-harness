package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.validation.StrictInstant;
import com.delivery.harness.gateway.platform.validation.StrictInteger;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Bounded typed HTTP requests for knowledge. */
public final class KnowledgeRequests {
    private KnowledgeRequests() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KnowledgeRequest(
            @NotBlank @Size(max = 256) @JsonDeserialize(using = StrictString.class) String title,
            @NotBlank @Size(max = 100_000) @JsonDeserialize(using = StrictString.class)
                    String content,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String documentId,
            @Pattern(regexp = "document|case") @JsonDeserialize(using = StrictString.class)
                    String sourceType,
            @Size(max = 30) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> tags,
            @Pattern(regexp = "abnormal_order_analysis|compensation_suggestion")
                    @JsonDeserialize(using = StrictString.class)
                    String scenario,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> cityCodes,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> stationIds,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveFrom,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveTo)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SearchRequest(
            @NotBlank @Size(max = 1000) @JsonDeserialize(using = StrictString.class) String query,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String cityCode,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String stationId,
            @JsonDeserialize(using = StrictInstant.class) Instant asOf,
            @Size(max = 64) @JsonDeserialize(using = StrictString.class) String scenario,
            @Min(1) @Max(100) @JsonDeserialize(using = StrictInteger.class) Integer topK,
            @Min(1) @Max(1000) @JsonDeserialize(using = StrictInteger.class) Integer scanLimit,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String policyCursor,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String knowledgeCursor)
            implements QualityRequests.Request {}
}
