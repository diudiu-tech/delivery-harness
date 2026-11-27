package com.delivery.harness.cases;

import com.delivery.harness.diagnosis.ResidualDiagnosis;
import com.delivery.harness.policy.RoutingAction;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.template.CustomerDraft;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable record of one deterministic (plus optional extraction) assessment
 * execution on a case.
 *
 * <p>Deliberately omits {@code caseStatus}, which belongs exclusively to
 * {@link DeliveryCase} so submitted decisions never leave stale status fields
 * inside historical runs.
 */
public record AssessmentRun(
        String runId,
        String traceId,
        int runNumber,
        Instant executedAt,
        String snapshotId,
        String snapshotHash,
        String policyVersion,
        String policyHash,
        RunHealth runHealth,
        List<String> degradedReasons,
        ResidualDiagnosis diagnosis,
        SingleSourcePolicyEngine.AdjudicationResult adjudication,
        RoutingAction routingAction,
        CustomerDraft customerDraft,
        List<Map<String, Object>> unconfirmedSignals,
        boolean untrustedTextDetected,
        boolean verificationFailed) {}
