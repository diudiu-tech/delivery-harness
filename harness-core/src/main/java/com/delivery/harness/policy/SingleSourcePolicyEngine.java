package com.delivery.harness.policy;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure deterministic policy adjudication engine.
 *
 * <p>By signature and architecture, this engine never receives LLM-extracted
 * signals ({@code ClaimedFact}); payout eligibility and amounts are invariant
 * to LLM behavior in both directions.
 */
@Service
@RequiredArgsConstructor
public class SingleSourcePolicyEngine {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String DELIVERED_STATUS = "DELIVERED";

    private final PolicyBundleLoader bundleLoader;

    public AdjudicationResult evaluate(PolicyEvaluationInput input) {
        return evaluate(bundleLoader.activeBundle(), bundleLoader.activePolicyHash(), input);
    }

    public AdjudicationResult evaluate(PolicyBundle bundle, String policyHash, PolicyEvaluationInput input) {
        if (input == null || input.complaintType() == null) {
            return AdjudicationResult.withoutRule(
                    AdjudicationStatus.NO_APPLICABLE_POLICY, bundle.policyVersion(), policyHash,
                    List.of("MISSING_COMPLAINT_TYPE"), List.of(), List.of("投诉类型缺失或不在政策范围内"));
        }

        // 1. Scope check: order status must be known and DELIVERED.
        EvidenceValue<String> statusEvidence = input.orderStatus();
        if (statusEvidence == null || !statusEvidence.isKnown()) {
            return AdjudicationResult.withoutRule(
                    AdjudicationStatus.NEEDS_EVIDENCE, bundle.policyVersion(), policyHash,
                    List.of(EvidenceType.ORDER_STATUS.name()), List.of(),
                    List.of("订单状态缺失或非法，无法判定适用范围"));
        }
        String orderStatus = statusEvidence.knownValue().orElse("");
        if (!DELIVERED_STATUS.equalsIgnoreCase(orderStatus)) {
            return AdjudicationResult.withoutRule(
                    AdjudicationStatus.OUT_OF_SCOPE, bundle.policyVersion(), policyHash,
                    List.of(), List.of(),
                    List.of("订单状态为 " + orderStatus + "，当前政策仅覆盖已完成订单（DELIVERED）"));
        }

        // 2. Effective window check against evaluation timestamp.
        if (input.evaluationAt() == null) {
            return AdjudicationResult.withoutRule(
                    AdjudicationStatus.NEEDS_EVIDENCE, bundle.policyVersion(), policyHash,
                    List.of(EvidenceType.ORDER_DELIVERY_TIME.name()), List.of(),
                    List.of("缺少政策适用时点（送达时间），无法匹配生效政策"));
        }
        if (input.evaluationAt().isBefore(bundle.effectiveFrom())
                || !input.evaluationAt().isBefore(bundle.effectiveTo())) {
            return AdjudicationResult.withoutRule(
                    AdjudicationStatus.NO_APPLICABLE_POLICY, bundle.policyVersion(), policyHash,
                    List.of(), List.of(),
                    List.of("订单时点不在当前政策包 " + bundle.policyVersion() + " 生效窗内"));
        }

        // 3. Candidate rules for this complaint type.
        List<PolicyBundle.CompensationRule> candidates = bundle.compensationRules().stream()
                .filter(r -> r.complaintType() == input.complaintType())
                .sorted(Comparator.comparingInt(PolicyBundle.CompensationRule::priority).reversed())
                .toList();
        if (candidates.isEmpty()) {
            return AdjudicationResult.withoutRule(
                    AdjudicationStatus.NO_APPLICABLE_POLICY, bundle.policyVersion(), policyHash,
                    List.of(), List.of(),
                    List.of("当前政策包未定义投诉类型 " + input.complaintType() + " 的赔付规则"));
        }

        // 4. Match specific rule (OVERTIME requires known overtimeMinutes first).
        PolicyBundle.CompensationRule matched;
        if (input.complaintType() == ComplaintType.OVERTIME) {
            EvidenceValue<Long> overtime = input.overtimeMinutes();
            if (overtime == null || !overtime.isKnown()) {
                return AdjudicationResult.withoutRule(
                        AdjudicationStatus.NEEDS_EVIDENCE, bundle.policyVersion(), policyHash,
                        List.of(EvidenceType.TIMELINE_OVERTIME_MINUTES.name()), List.of(),
                        List.of("超时分钟数缺失或时间轴异常，无法匹配超时赔付档位"));
            }
            long overtimeMinutes = overtime.knownValue().orElse(0L);
            Optional<PolicyBundle.CompensationRule> overtimeMatch = candidates.stream()
                    .filter(r -> matchesOvertime(r, overtimeMinutes))
                    .findFirst();
            if (overtimeMatch.isEmpty()) {
                return AdjudicationResult.withoutRule(
                        AdjudicationStatus.NO_APPLICABLE_POLICY, bundle.policyVersion(), policyHash,
                        List.of(), List.of(),
                        List.of("未命中任何超时赔付区间"));
            }
            matched = overtimeMatch.get();
        } else {
            matched = candidates.get(0);
        }

        // 5. Required evidence & staleness checks on matched rule.
        List<String> missing = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        checkRequiredEvidence(bundle, matched, input, missing, stale);
        if (!missing.isEmpty() || !stale.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            if (!missing.isEmpty()) {
                reasons.add("缺少必要证据: " + String.join(", ", missing));
            }
            if (!stale.isEmpty()) {
                reasons.add("必要证据已过期: " + String.join(", ", stale));
            }
            return new AdjudicationResult(
                    AdjudicationStatus.NEEDS_EVIDENCE,
                    bundle.policyVersion(),
                    policyHash,
                    matched.ruleId(),
                    matched.ruleName(),
                    matched.renderDescription(),
                    null,
                    null,
                    null,
                    true,
                    reasons,
                    List.copyOf(missing),
                    List.copyOf(stale));
        }

        // 6. Compute deterministic amount from single-source rule parameters.
        BigDecimal calculated = computeAmount(matched, input);
        if (calculated == null) {
            return new AdjudicationResult(
                    AdjudicationStatus.NEEDS_EVIDENCE,
                    bundle.policyVersion(),
                    policyHash,
                    matched.ruleId(),
                    matched.ruleName(),
                    matched.renderDescription(),
                    null,
                    null,
                    null,
                    true,
                    List.of("缺少计算赔付所需的必要字段"),
                    List.of(EvidenceType.ORDER_AMOUNT.name()),
                    List.of());
        }

        // 7. Escalation cap check: exceeding cap never surfaces an actionable amount.
        BigDecimal escalationCap = bundle.limits().escalationCap();
        if (calculated.compareTo(escalationCap) > 0) {
            return new AdjudicationResult(
                    AdjudicationStatus.EXCEEDS_CAP_ESCALATE,
                    bundle.policyVersion(),
                    policyHash,
                    matched.ruleId(),
                    matched.ruleName(),
                    matched.renderDescription(),
                    null,
                    calculated,
                    matched.method(),
                    true,
                    List.of("计算金额 " + calculated.toPlainString()
                            + " 元超过升级上限 " + escalationCap.toPlainString() + " 元，必须升级主管审批"),
                    List.of(),
                    List.of());
        }

        // 8. Distinguish explicit INELIGIBLE (0.00) from positive ELIGIBLE payout.
        if (calculated.compareTo(ZERO) == 0) {
            return new AdjudicationResult(
                    AdjudicationStatus.INELIGIBLE,
                    bundle.policyVersion(),
                    policyHash,
                    matched.ruleId(),
                    matched.ruleName(),
                    matched.renderDescription(),
                    ZERO,
                    ZERO,
                    matched.method(),
                    matched.approvalRequired(),
                    matched.approvalRequired() ? List.of("规则要求人工审批") : List.of(),
                    List.of(),
                    List.of());
        }

        BigDecimal approvalThreshold = bundle.limits().approvalThreshold();
        boolean exceedsApproval = calculated.compareTo(approvalThreshold) >= 0;
        boolean approvalRequired = matched.approvalRequired() || exceedsApproval;
        List<String> approvalReasons = new ArrayList<>();
        if (matched.approvalRequired()) {
            approvalReasons.add("策略规则 " + matched.ruleId() + " 要求人工审批");
        }
        if (exceedsApproval) {
            approvalReasons.add("赔付金额达到 " + approvalThreshold.toPlainString() + " 元审批线");
        }

        return new AdjudicationResult(
                AdjudicationStatus.ELIGIBLE,
                bundle.policyVersion(),
                policyHash,
                matched.ruleId(),
                matched.ruleName(),
                matched.renderDescription(),
                calculated,
                calculated,
                matched.method(),
                approvalRequired,
                List.copyOf(approvalReasons),
                List.of(),
                List.of());
    }

    /**
     * Deterministic risk routing using the bundle's validated routing matrix.
     * Unmapped states default conservatively to {@link RoutingAction#ESCALATED}.
     */
    public RoutingAction route(
            AdjudicationResult result,
            boolean hasUnconfirmedConflictSignal,
            boolean hasVerificationFailure,
            boolean untrustedTextDetected) {
        return route(bundleLoader.activeBundle(), result,
                hasUnconfirmedConflictSignal, hasVerificationFailure, untrustedTextDetected);
    }

    public RoutingAction route(
            PolicyBundle bundle,
            AdjudicationResult result,
            boolean hasUnconfirmedConflictSignal,
            boolean hasVerificationFailure,
            boolean untrustedTextDetected) {
        PolicyBundle.RoutingMatrix matrix = bundle.routingMatrix();
        RoutingAction base = matrix.baseByAdjudication()
                .getOrDefault(result.status(), RoutingAction.ESCALATED);

        RoutingAction chosen = base;
        if (result.approvalRequired() && result.status() != AdjudicationStatus.NEEDS_EVIDENCE) {
            chosen = maxSeverity(chosen, matrix.onApprovalRequired());
        }
        if (hasUnconfirmedConflictSignal) {
            chosen = maxSeverity(chosen, matrix.onUnconfirmedConflictSignal());
        }
        if (hasVerificationFailure) {
            chosen = maxSeverity(chosen, matrix.onVerificationFailure());
        }
        if (untrustedTextDetected) {
            chosen = maxSeverity(chosen, matrix.onUntrustedText());
        }
        return chosen;
    }

    /**
     * Checks staleness dynamically from a {@link EvidenceValue.Known}'s
     * {@code observedAt} and {@code asOf} timestamps against the bundle's
     * {@code stalenessPolicySeconds}.
     */
    public static boolean isStale(PolicyBundle bundle, EvidenceType type, EvidenceValue.Known<?> known) {
        if (bundle.stalenessPolicySeconds() == null || type == null || known == null) {
            return false;
        }
        Long maxAgeSeconds = bundle.stalenessPolicySeconds().get(type);
        if (maxAgeSeconds == null) {
            return false;
        }
        long ageSeconds = Math.abs(Duration.between(known.asOf(), known.observedAt()).getSeconds());
        return ageSeconds > maxAgeSeconds;
    }

    private static RoutingAction maxSeverity(RoutingAction a, RoutingAction b) {
        if (a == RoutingAction.ESCALATED || b == RoutingAction.ESCALATED) {
            return RoutingAction.ESCALATED;
        }
        if (a == RoutingAction.WAITING_EVIDENCE || b == RoutingAction.WAITING_EVIDENCE) {
            return RoutingAction.WAITING_EVIDENCE;
        }
        return RoutingAction.PENDING_DECISION;
    }

    private static boolean matchesOvertime(PolicyBundle.CompensationRule rule, long overtimeMinutes) {
        boolean aboveMin = rule.minOvertimeMinutes() == null || overtimeMinutes >= rule.minOvertimeMinutes();
        boolean belowMax = rule.maxOvertimeMinutes() == null || overtimeMinutes <= rule.maxOvertimeMinutes();
        return aboveMin && belowMax;
    }

    private static void checkRequiredEvidence(
            PolicyBundle bundle,
            PolicyBundle.CompensationRule rule,
            PolicyEvaluationInput input,
            List<String> missing,
            List<String> stale) {
        if (rule.requiredEvidence() == null) {
            return;
        }
        for (EvidenceType type : rule.requiredEvidence()) {
            EvidenceValue<?> ev = resolveEvidence(type, input);
            if (ev == null) {
                missing.add(type.name());
                continue;
            }
            ev.fold(
                    known -> {
                        if (isStale(bundle, type, known)) {
                            stale.add(type.name());
                        }
                        return null;
                    },
                    unknown -> {
                        missing.add(type.name());
                        return null;
                    },
                    invalid -> {
                        missing.add(type.name() + "(INVALID)");
                        return null;
                    });
        }
    }

    private static EvidenceValue<?> resolveEvidence(EvidenceType type, PolicyEvaluationInput input) {
        if (input.presentEvidence() != null && input.presentEvidence().containsKey(type)) {
            return input.presentEvidence().get(type);
        }
        return switch (type) {
            case ORDER_STATUS -> input.orderStatus();
            case ORDER_AMOUNT -> input.orderAmount();
            case TIMELINE_OVERTIME_MINUTES -> input.overtimeMinutes();
            case DAMAGE_LEVEL -> input.damageLevel();
            default -> null;
        };
    }

    private static BigDecimal computeAmount(PolicyBundle.CompensationRule rule, PolicyEvaluationInput input) {
        return switch (rule.amountType()) {
            case FIXED -> rule.fixedAmount().setScale(2, RoundingMode.HALF_UP);
            case PERCENT_CAPPED -> {
                BigDecimal orderAmount = validOrderAmount(input.orderAmount());
                if (orderAmount == null) {
                    yield null;
                }
                yield percentageCapped(orderAmount, rule.rate(), rule.cap());
            }
            case DAMAGE_LEVEL -> {
                BigDecimal orderAmount = validOrderAmount(input.orderAmount());
                DamageLevel damageLevel = input.damageLevel() == null
                        ? null : input.damageLevel().knownValue().orElse(null);
                if (orderAmount == null || damageLevel == null) {
                    yield null;
                }
                yield damageLevel == DamageLevel.FULL
                        ? orderAmount.setScale(2, RoundingMode.HALF_UP)
                        : percentageCapped(orderAmount, rule.partialRate(), rule.partialCap());
            }
            case MANUAL -> null;
        };
    }

    private static BigDecimal validOrderAmount(EvidenceValue<BigDecimal> orderAmount) {
        if (orderAmount == null || !orderAmount.isKnown()) {
            return null;
        }
        BigDecimal value = orderAmount.knownValue().orElse(null);
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        return value;
    }

    private static BigDecimal percentageCapped(BigDecimal orderAmount, BigDecimal rate, BigDecimal cap) {
        BigDecimal raw = orderAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return raw.min(cap).setScale(2, RoundingMode.HALF_UP);
    }

    public record PolicyEvaluationInput(
            EvidenceValue<String> orderStatus,
            ComplaintType complaintType,
            EvidenceValue<Long> overtimeMinutes,
            EvidenceValue<BigDecimal> orderAmount,
            EvidenceValue<DamageLevel> damageLevel,
            Instant evaluationAt,
            Map<EvidenceType, EvidenceValue<?>> presentEvidence) {}

    public record AdjudicationResult(
            AdjudicationStatus status,
            String policyVersion,
            String policyHash,
            String matchedRuleId,
            String matchedRuleName,
            String renderedPolicyText,
            BigDecimal actionableAmount,
            BigDecimal calculatedAmount,
            String suggestedMethod,
            boolean approvalRequired,
            List<String> approvalReasons,
            List<String> missingEvidence,
            List<String> staleEvidence) {

        static AdjudicationResult withoutRule(
                AdjudicationStatus status,
                String policyVersion,
                String policyHash,
                List<String> missingEvidence,
                List<String> staleEvidence,
                List<String> reasons) {
            return new AdjudicationResult(
                    status, policyVersion, policyHash,
                    null, null, null,
                    null, null, null,
                    true,
                    List.copyOf(reasons),
                    List.copyOf(missingEvidence),
                    List.copyOf(staleEvidence));
        }
    }
}
