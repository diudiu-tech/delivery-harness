package com.delivery.harness.cases;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Structured human decision bound to a specific {@code runId}.
 *
 * <p>In this unauthenticated open-source reference build, {@code operatorId} is
 * caller-supplied and explicitly tagged as {@code unauthenticated_operator_id}.
 */
public record HumanDecision(
        String decisionId,
        String runId,
        DecisionAction action,
        String operatorId,
        String operatorIdentityMode,
        String overrideReasonCode,
        BigDecimal finalAmount,
        String finalMethod,
        String finalLiability,
        String exceptionReason,
        String externalReceiptId,
        String note,
        Instant decidedAt) {

    public static final String UNAUTHENTICATED_OPERATOR_MODE = "unauthenticated_operator_id";
}
