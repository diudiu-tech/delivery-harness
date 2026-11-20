package com.delivery.harness.gateway.controller;

import com.delivery.harness.application.CaseApplicationService;
import com.delivery.harness.cases.DecisionAction;
import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.common.dto.HarnessResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unified Case API replacing the split v1 {@code /api/v1/analyze/*} endpoints.
 *
 * <p>Business creation intentionally does not expose {@code mode} or
 * {@code policyVersion} override parameters so callers cannot shop for a more
 * lenient policy.
 */
@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
@Validated
public class CaseController {

    private final CaseApplicationService caseApplicationService;

    @PostMapping
    public ResponseEntity<HarnessResponse<DeliveryCase>> createCase(
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateCaseRequest request) {
        CaseApplicationService.CreateCaseResult result = caseApplicationService.createCase(
                new CaseApplicationService.CreateCaseCommand(
                        request.getOrderId(), request.getComplaintType(), idempotencyKey));
        HttpStatus status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(HarnessResponse.success(result.deliveryCase()));
    }

    @GetMapping
    public HarnessResponse<List<DeliveryCase>> listCases(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return HarnessResponse.success(caseApplicationService.listCases(limit));
    }

    @GetMapping("/{caseId}")
    public HarnessResponse<DeliveryCase> getCase(@PathVariable String caseId) {
        return HarnessResponse.success(caseApplicationService.getCase(caseId));
    }

    @PostMapping("/{caseId}/attestations")
    public HarnessResponse<DeliveryCase> attestEvidence(
            @PathVariable String caseId,
            @Valid @RequestBody AttestEvidenceRequest request) {
        DeliveryCase updated = caseApplicationService.attestEvidence(
                caseId,
                new CaseApplicationService.AttestCommand(
                        request.getEvidenceType(),
                        request.getValue(),
                        request.getOperatorId(),
                        request.getExpectedCaseVersion()));
        return HarnessResponse.success(updated);
    }

    @PostMapping("/{caseId}/decisions")
    public HarnessResponse<DeliveryCase> submitDecision(
            @PathVariable String caseId,
            @Valid @RequestBody SubmitDecisionRequest request) {
        DeliveryCase updated = caseApplicationService.submitDecision(
                caseId,
                new CaseApplicationService.DecisionCommand(
                        request.getRunId(),
                        request.getExpectedCaseVersion(),
                        request.getAction(),
                        request.getOperatorId(),
                        request.getOverrideReasonCode(),
                        request.getFinalAmount(),
                        request.getFinalMethod(),
                        request.getFinalLiability(),
                        request.getExceptionReason(),
                        request.getExternalReceiptId(),
                        request.getNote()));
        return HarnessResponse.success(updated);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCaseRequest {
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "order_id contains unsupported characters")
        @JsonProperty("order_id")
        private String orderId;

        @NotBlank
        @Pattern(regexp = "OVERTIME|WRONG_ORDER|DAMAGED|MISSING_ITEM")
        @JsonProperty("complaint_type")
        private String complaintType = "OVERTIME";
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttestEvidenceRequest {
        @NotBlank
        @Size(max = 64)
        @JsonProperty("evidence_type")
        private String evidenceType;

        @NotBlank
        @Size(max = 64)
        private String value;

        @NotBlank
        @Size(max = 128)
        @JsonProperty("operator_id")
        private String operatorId;

        @NotNull
        @Min(1)
        @JsonProperty("expected_case_version")
        private Integer expectedCaseVersion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitDecisionRequest {
        @NotBlank
        @Size(max = 64)
        @JsonProperty("run_id")
        private String runId;

        @NotNull
        @Min(1)
        @JsonProperty("expected_case_version")
        private Integer expectedCaseVersion;

        @NotNull
        private DecisionAction action;

        @NotBlank
        @Size(max = 128)
        @JsonProperty("operator_id")
        private String operatorId;

        @Size(max = 64)
        @JsonProperty("override_reason_code")
        private String overrideReasonCode;

        @JsonProperty("final_amount")
        private BigDecimal finalAmount;

        @Size(max = 64)
        @JsonProperty("final_method")
        private String finalMethod;

        @Size(max = 64)
        @JsonProperty("final_liability")
        private String finalLiability;

        @Size(max = 500)
        @JsonProperty("exception_reason")
        private String exceptionReason;

        @Size(max = 128)
        @JsonProperty("external_receipt_id")
        private String externalReceiptId;

        @Size(max = 1000)
        private String note;
    }
}
