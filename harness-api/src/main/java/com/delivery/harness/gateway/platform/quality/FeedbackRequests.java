package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.validation.StrictLong;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Bounded typed HTTP requests for feedback. */
public final class FeedbackRequests {
    private FeedbackRequests() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FeedbackRequest(
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class) String caseId,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String decisionId,
            @JsonDeserialize(using = StrictLong.class) @NotNull @Min(1) Long decisionVersion,
            @NotBlank
                    @Pattern(
                            regexp =
                                    "ATTRIBUTION_ERROR|RULE_ERROR|AMOUNT_ERROR|EVIDENCE_ERROR|MESSAGE_ERROR|USABILITY")
                    @JsonDeserialize(using = StrictString.class)
                    String category,
            @NotNull @Valid Correction correction,
            @Size(max = 200) @JsonDeserialize(using = StrictString.class) String submittedBy)
            implements QualityRequests.Request {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Correction(
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String fieldPath,
            @BoundedJson JsonNode originalValue,
            @BoundedJson JsonNode suggestedValue,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictString.class)
                    String reasonCode,
            @Size(max = 100) @JsonDeserialize(contentUsing = StrictString.class)
                    List<@NotBlank @Size(max = 100) String> evidenceRefs) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FeedbackStatusRequest(
            @JsonDeserialize(using = StrictLong.class) @NotNull @Min(1) Long expectedVersion,
            @NotBlank
                    @Pattern(regexp = "CONFIRMED|REJECTED|LINKED_FIX|REGRESSION_VERIFIED")
                    @JsonDeserialize(using = StrictString.class)
                    String status,
            @NotBlank @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String reason,
            @Size(max = 256) @JsonDeserialize(using = StrictString.class) String issueRef,
            @Size(max = 100) @JsonDeserialize(using = StrictString.class) String evalRunId)
            implements QualityRequests.Request {}
}
