package com.delivery.harness.gateway.platform.casework;

import com.delivery.harness.gateway.platform.validation.PlatformRequest;
import com.delivery.harness.gateway.platform.validation.StrictLong;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CaseRequests {
    private CaseRequests() {}

    public record Create(
            @JsonProperty("order_id")
                    @NotBlank
                    @Size(max = 120)
                    @Pattern(regexp = "[A-Za-z0-9_.:-]+")
                    @JsonDeserialize(using = StrictString.class)
                    String orderId,
            @JsonProperty("complaint_type")
                    @Pattern(regexp = "OVERTIME|DAMAGED|MISSING_ITEM|WRONG_ORDER")
                    @JsonDeserialize(using = StrictString.class)
                    String complaintType,
            @JsonProperty("source_ticket_id")
                    @Size(max = 160)
                    @JsonDeserialize(using = StrictString.class)
                    String sourceTicketId,
            @Size(max = 200) @JsonDeserialize(using = StrictString.class) String title)
            implements PlatformRequest {}

    public record Material(
            @JsonProperty("file_hash")
                    @NotBlank
                    @Pattern(regexp = "[a-fA-F0-9]{64}")
                    @JsonDeserialize(using = StrictString.class)
                    String fileHash,
            @JsonProperty("mime_type")
                    @NotBlank
                    @Pattern(regexp = "image/png|image/jpeg|application/pdf|text/plain")
                    @JsonDeserialize(using = StrictString.class)
                    String mimeType,
            @JsonProperty("size_bytes")
                    @NotNull
                    @Min(1)
                    @Max(10_000_000)
                    @JsonDeserialize(using = StrictLong.class)
                    Long sizeBytes,
            @Size(max = 80) @JsonDeserialize(using = StrictString.class) String source,
            @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String description,
            @JsonProperty("content_base64")
                    @Size(max = 1_333_336)
                    @JsonDeserialize(using = StrictString.class)
                    String contentBase64,
            @JsonProperty("reference_uri")
                    @Size(max = 2048)
                    @Pattern(regexp = "https://.+")
                    @JsonDeserialize(using = StrictString.class)
                    String referenceUri,
            @JsonProperty("damage_level")
                    @Pattern(regexp = "PARTIAL|FULL")
                    @JsonDeserialize(using = StrictString.class)
                    String damageLevel)
            implements PlatformRequest {}

    public record MaterialReview(
            @JsonProperty("expected_version")
                    @NotNull
                    @Min(1)
                    @JsonDeserialize(using = StrictLong.class)
                    Long expectedVersion,
            @NotBlank
                    @Pattern(regexp = "VERIFY|REJECT")
                    @JsonDeserialize(using = StrictString.class)
                    String action,
            @NotBlank @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String reason)
            implements PlatformRequest {}

    public record Claim(
            @JsonProperty("expected_version")
                    @NotNull
                    @Min(1)
                    @JsonDeserialize(using = StrictLong.class)
                    Long expectedVersion)
            implements PlatformRequest {}

    public record Action(
            @JsonProperty("expected_version")
                    @NotNull
                    @Min(1)
                    @JsonDeserialize(using = StrictLong.class)
                    Long expectedVersion,
            @NotBlank
                    @Pattern(regexp = "APPROVE|REJECT|REQUEST_EVIDENCE|REASSIGN")
                    @JsonDeserialize(using = StrictString.class)
                    String action,
            @NotBlank @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String reason,
            @JsonProperty("decision_id")
                    @NotBlank
                    @Size(max = 160)
                    @JsonDeserialize(using = StrictString.class)
                    String decisionId,
            @JsonProperty("decision_version")
                    @NotNull
                    @Min(1)
                    @JsonDeserialize(using = StrictLong.class)
                    Long decisionVersion,
            @JsonProperty("evidence_snapshot_id")
                    @NotBlank
                    @Size(max = 160)
                    @JsonDeserialize(using = StrictString.class)
                    String evidenceSnapshotId,
            @JsonProperty("target_user_id")
                    @Size(max = 160)
                    @JsonDeserialize(using = StrictString.class)
                    String targetUserId)
            implements PlatformRequest {}

    public record Close(
            @JsonProperty("expected_version")
                    @NotNull
                    @Min(1)
                    @JsonDeserialize(using = StrictLong.class)
                    Long expectedVersion,
            @NotBlank @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String reason,
            @JsonProperty("external_receipt")
                    @Size(max = 2000)
                    @JsonDeserialize(using = StrictString.class)
                    String externalReceipt)
            implements PlatformRequest {}
}
