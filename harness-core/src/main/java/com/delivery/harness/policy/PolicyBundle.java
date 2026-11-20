package com.delivery.harness.policy;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.MinuteRoundingMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable single-source-of-truth policy specification.
 *
 * <p>All payout bounds, approval thresholds, escalation caps, attribution
 * thresholds, staleness tolerances, rounding semantics, and routing mappings
 * live here rather than being split across application.yml and Java constants.
 * Human-readable policy prose is rendered directly from these typed fields so
 * documentation and execution cannot drift.
 */
public record PolicyBundle(
        String policyVersion,
        Instant effectiveFrom,
        Instant effectiveTo,
        String evaluationClockField,
        MinuteRoundingMode minuteRoundingMode,
        Map<EvidenceType, Long> stalenessPolicySeconds,
        AttributionThresholds attributionThresholds,
        Limits limits,
        List<CompensationRule> compensationRules,
        RoutingMatrix routingMatrix) {

    public enum AmountType {
        FIXED,
        PERCENT_CAPPED,
        DAMAGE_LEVEL,
        MANUAL
    }

    public record Limits(
            BigDecimal approvalThreshold,
            BigDecimal escalationCap) {}

    public record AttributionThresholds(
            long minExcessMinutes,
            long dominanceMarginMinutes,
            long normalDispatchBaselineMinutes,
            long toShopBaselineMinutes,
            long normalMerchantPrepBaselineMinutes,
            double strainedCapacityRatio,
            String rationale) {}

    public record CompensationRule(
            String ruleId,
            String ruleName,
            ComplaintType complaintType,
            int priority,
            List<EvidenceType> requiredEvidence,
            Integer minOvertimeMinutes,
            Integer maxOvertimeMinutes,
            AmountType amountType,
            BigDecimal fixedAmount,
            BigDecimal rate,
            BigDecimal cap,
            BigDecimal partialRate,
            BigDecimal partialCap,
            String method,
            boolean approvalRequired) {

        /** Auto-renders authoritative prose directly from executable rule parameters. */
        public String renderDescription() {
            StringBuilder sb = new StringBuilder();
            if (complaintType == ComplaintType.OVERTIME) {
                if (minOvertimeMinutes != null && maxOvertimeMinutes != null) {
                    if (minOvertimeMinutes.equals(maxOvertimeMinutes)) {
                        sb.append("超时 ").append(minOvertimeMinutes).append(" 分钟");
                    } else {
                        sb.append("超时 ").append(minOvertimeMinutes)
                                .append(" 至 ").append(maxOvertimeMinutes).append(" 分钟");
                    }
                } else if (minOvertimeMinutes != null) {
                    sb.append("超时 ").append(minOvertimeMinutes).append(" 分钟及以上");
                } else if (maxOvertimeMinutes != null) {
                    sb.append("超时 ").append(maxOvertimeMinutes).append(" 分钟及以下");
                }
            } else {
                sb.append(ruleName);
            }

            sb.append("：");
            switch (amountType) {
                case FIXED -> sb.append(method)
                        .append("（").append(scale(fixedAmount)).append(" 元）");
                case PERCENT_CAPPED -> sb.append(method)
                        .append("（订单金额 ").append(percentText(rate))
                        .append("，上限 ").append(scale(cap)).append(" 元）");
                case DAMAGE_LEVEL -> sb.append(method)
                        .append("（需核验 DAMAGE_LEVEL：全损按订单金额 100%，部分损坏按 ")
                        .append(percentText(partialRate))
                        .append(" 且上限 ").append(scale(partialCap)).append(" 元）");
                case MANUAL -> sb.append("需人工判断");
            }
            if (approvalRequired) {
                sb.append("，需人工审批");
            }
            sb.append("。");
            return sb.toString();
        }

        private static String scale(BigDecimal value) {
            return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        private static String percentText(BigDecimal rate) {
            if (rate == null) {
                return "0%";
            }
            return rate.multiply(BigDecimal.valueOf(100))
                    .stripTrailingZeros().toPlainString() + "%";
        }
    }

    public record RoutingMatrix(
            Map<AdjudicationStatus, RoutingAction> baseByAdjudication,
            RoutingAction onApprovalRequired,
            RoutingAction onUnconfirmedConflictSignal,
            RoutingAction onVerificationFailure,
            RoutingAction onUntrustedText) {}
}
