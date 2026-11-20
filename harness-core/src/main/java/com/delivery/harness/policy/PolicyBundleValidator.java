package com.delivery.harness.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-fast startup validator for {@link PolicyBundle}.
 *
 * <p>Guarantees that one-dimensional overtime intervals have no overlaps and
 * no gaps, that every {@link AdjudicationStatus} has an explicit routing entry,
 * and that every rule carries the parameters required by its {@code amountType}.
 */
public final class PolicyBundleValidator {

    private PolicyBundleValidator() {}

    public static void validate(PolicyBundle bundle) {
        List<String> errors = collectErrors(bundle);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid PolicyBundle: " + String.join("; ", errors));
        }
    }

    public static List<String> collectErrors(PolicyBundle bundle) {
        List<String> errors = new ArrayList<>();
        if (bundle == null) {
            errors.add("bundle is null");
            return errors;
        }
        if (bundle.policyVersion() == null || bundle.policyVersion().isBlank()) {
            errors.add("policyVersion must not be blank");
        }
        if (bundle.effectiveFrom() == null || bundle.effectiveTo() == null
                || !bundle.effectiveFrom().isBefore(bundle.effectiveTo())) {
            errors.add("effectiveFrom must be strictly before effectiveTo");
        }
        if (bundle.minuteRoundingMode() == null) {
            errors.add("minuteRoundingMode is required");
        }

        validateLimits(bundle.limits(), errors);
        validateAttributionThresholds(bundle.attributionThresholds(), errors);
        validateRules(bundle.compensationRules(), errors);
        validateRoutingMatrix(bundle.routingMatrix(), errors);
        return errors;
    }

    private static void validateLimits(PolicyBundle.Limits limits, List<String> errors) {
        if (limits == null) {
            errors.add("limits is required");
            return;
        }
        if (limits.approvalThreshold() == null || limits.approvalThreshold().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("limits.approvalThreshold must be >= 0");
        }
        if (limits.escalationCap() == null || limits.escalationCap().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("limits.escalationCap must be > 0");
        }
        if (limits.approvalThreshold() != null && limits.escalationCap() != null
                && limits.approvalThreshold().compareTo(limits.escalationCap()) > 0) {
            errors.add("limits.approvalThreshold must be <= limits.escalationCap");
        }
    }

    private static void validateAttributionThresholds(
            PolicyBundle.AttributionThresholds thresholds, List<String> errors) {
        if (thresholds == null) {
            errors.add("attributionThresholds is required");
            return;
        }
        if (thresholds.minExcessMinutes() <= 0) {
            errors.add("attributionThresholds.minExcessMinutes must be > 0");
        }
        if (thresholds.dominanceMarginMinutes() < 0) {
            errors.add("attributionThresholds.dominanceMarginMinutes must be >= 0");
        }
        if (thresholds.normalDispatchBaselineMinutes() < 0
                || thresholds.toShopBaselineMinutes() < 0
                || thresholds.normalMerchantPrepBaselineMinutes() < 0) {
            errors.add("attributionThresholds baselines must be >= 0");
        }
        if (thresholds.strainedCapacityRatio() <= 0.0 || thresholds.strainedCapacityRatio() > 1.0) {
            errors.add("attributionThresholds.strainedCapacityRatio must be in (0, 1]");
        }
    }

    private static void validateRules(List<PolicyBundle.CompensationRule> rules, List<String> errors) {
        if (rules == null || rules.isEmpty()) {
            errors.add("compensationRules must not be empty");
            return;
        }
        Set<String> seenIds = new HashSet<>();
        for (PolicyBundle.CompensationRule rule : rules) {
            if (rule.ruleId() == null || rule.ruleId().isBlank()) {
                errors.add("ruleId must not be blank");
            } else if (!seenIds.add(rule.ruleId())) {
                errors.add("duplicate ruleId: " + rule.ruleId());
            }
            if (rule.complaintType() == null) {
                errors.add("rule " + rule.ruleId() + " missing complaintType");
            }
            if (rule.amountType() == null) {
                errors.add("rule " + rule.ruleId() + " missing amountType");
                continue;
            }
            switch (rule.amountType()) {
                case FIXED -> {
                    if (rule.fixedAmount() == null || rule.fixedAmount().compareTo(BigDecimal.ZERO) < 0) {
                        errors.add("rule " + rule.ruleId() + " requires fixedAmount >= 0");
                    }
                }
                case PERCENT_CAPPED -> {
                    if (rule.rate() == null || rule.rate().compareTo(BigDecimal.ZERO) <= 0
                            || rule.cap() == null || rule.cap().compareTo(BigDecimal.ZERO) <= 0) {
                        errors.add("rule " + rule.ruleId() + " requires positive rate and cap");
                    }
                }
                case DAMAGE_LEVEL -> {
                    if (rule.partialRate() == null || rule.partialRate().compareTo(BigDecimal.ZERO) <= 0
                            || rule.partialCap() == null || rule.partialCap().compareTo(BigDecimal.ZERO) <= 0) {
                        errors.add("rule " + rule.ruleId() + " requires positive partialRate and partialCap");
                    }
                }
                case MANUAL -> {
                    // no numeric parameters required
                }
            }
        }

        Map<ComplaintType, List<PolicyBundle.CompensationRule>> byType = rules.stream()
                .filter(r -> r.complaintType() != null)
                .collect(Collectors.groupingBy(PolicyBundle.CompensationRule::complaintType));

        List<PolicyBundle.CompensationRule> overtimeRules = byType.get(ComplaintType.OVERTIME);
        if (overtimeRules != null && !overtimeRules.isEmpty()) {
            validateContiguousOvertimeIntervals(overtimeRules, errors);
        }
    }

    private static void validateContiguousOvertimeIntervals(
            List<PolicyBundle.CompensationRule> overtimeRules, List<String> errors) {
        for (PolicyBundle.CompensationRule rule : overtimeRules) {
            if (rule.minOvertimeMinutes() == null || rule.minOvertimeMinutes() < 0) {
                errors.add("OVERTIME rule " + rule.ruleId() + " must declare minOvertimeMinutes >= 0");
                return;
            }
            if (rule.maxOvertimeMinutes() != null && rule.maxOvertimeMinutes() < rule.minOvertimeMinutes()) {
                errors.add("OVERTIME rule " + rule.ruleId() + " has maxOvertimeMinutes < minOvertimeMinutes");
                return;
            }
        }

        List<PolicyBundle.CompensationRule> sorted = overtimeRules.stream()
                .sorted(Comparator.comparingInt(PolicyBundle.CompensationRule::minOvertimeMinutes))
                .toList();

        if (sorted.get(0).minOvertimeMinutes() != 0) {
            errors.add("OVERTIME intervals must start at 0, found " + sorted.get(0).minOvertimeMinutes());
        }

        for (int i = 0; i < sorted.size() - 1; i++) {
            PolicyBundle.CompensationRule current = sorted.get(i);
            PolicyBundle.CompensationRule next = sorted.get(i + 1);
            if (current.maxOvertimeMinutes() == null) {
                errors.add("OVERTIME rule " + current.ruleId() + " is unbounded but overlaps " + next.ruleId());
                return;
            }
            int expectedNextMin = current.maxOvertimeMinutes() + 1;
            if (next.minOvertimeMinutes() < expectedNextMin) {
                errors.add("OVERTIME interval overlap between " + current.ruleId() + " and " + next.ruleId());
            } else if (next.minOvertimeMinutes() > expectedNextMin) {
                errors.add("OVERTIME interval gap between " + current.ruleId()
                        + " (max=" + current.maxOvertimeMinutes() + ") and "
                        + next.ruleId() + " (min=" + next.minOvertimeMinutes() + ")");
            }
        }

        PolicyBundle.CompensationRule last = sorted.get(sorted.size() - 1);
        if (last.maxOvertimeMinutes() != null) {
            errors.add("OVERTIME top tier " + last.ruleId() + " must be unbounded (maxOvertimeMinutes=null)");
        }
    }

    private static void validateRoutingMatrix(PolicyBundle.RoutingMatrix matrix, List<String> errors) {
        if (matrix == null) {
            errors.add("routingMatrix is required");
            return;
        }
        if (matrix.baseByAdjudication() == null) {
            errors.add("routingMatrix.baseByAdjudication is required");
        } else {
            for (AdjudicationStatus status : AdjudicationStatus.values()) {
                if (matrix.baseByAdjudication().get(status) == null) {
                    errors.add("routingMatrix missing mapping for AdjudicationStatus." + status.name());
                }
            }
        }
        if (matrix.onApprovalRequired() == null
                || matrix.onUnconfirmedConflictSignal() == null
                || matrix.onVerificationFailure() == null
                || matrix.onUntrustedText() == null) {
            errors.add("routingMatrix override actions must all be non-null");
        }
    }
}
