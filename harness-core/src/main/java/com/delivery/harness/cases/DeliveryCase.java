package com.delivery.harness.cases;

import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.policy.ComplaintType;
import com.delivery.harness.template.CustomerDraft;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Top-level case aggregate owning the single case state machine, optimistic
 * concurrency version, immutable latest snapshot, append-only runs, append-only
 * human decisions, approved payout intent, and optional external execution receipt.
 */
public record DeliveryCase(
        String caseId,
        String orderId,
        ComplaintType complaintType,
        CaseStatus status,
        int version,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        EvidenceSnapshot latestSnapshot,
        List<AssessmentRun> runs,
        List<HumanDecision> decisions,
        BigDecimal pendingOverrideAmount,
        BigDecimal approvedPayoutAmount,
        String approvedPayoutMethod,
        String executionReceiptId,
        CustomerDraft latestCustomerDraft) {

    public AssessmentRun latestRun() {
        if (runs == null || runs.isEmpty()) {
            throw new IllegalStateException("Case has no assessment runs: " + caseId);
        }
        return runs.get(runs.size() - 1);
    }
}
