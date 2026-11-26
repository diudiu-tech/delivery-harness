package com.delivery.harness.gateway.platform.operations;

import com.delivery.harness.gateway.platform.validation.PlatformRequest;
import com.delivery.harness.gateway.platform.validation.StrictDecimal;
import com.delivery.harness.gateway.platform.validation.StrictLong;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ExceptionRequest(
        @JsonProperty("expected_case_version")
                @NotNull
                @Min(1)
                @JsonDeserialize(using = StrictLong.class)
                Long expectedCaseVersion,
        @JsonProperty("evidence_snapshot_id")
                @NotBlank
                @Size(max = 160)
                @JsonDeserialize(using = StrictString.class)
                String evidenceSnapshotId,
        @NotNull
                @DecimalMin("0.00")
                @Digits(integer = 8, fraction = 2)
                @JsonDeserialize(using = StrictDecimal.class)
                BigDecimal amount,
        @NotBlank
                @Pattern(regexp = "REFUND|COUPON|RED_PACKET|RESEND")
                @JsonDeserialize(using = StrictString.class)
                String method,
        @NotBlank @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String reason,
        @JsonProperty("authorization_basis")
                @NotBlank
                @Size(max = 2000)
                @JsonDeserialize(using = StrictString.class)
                String authorizationBasis)
        implements PlatformRequest {}
