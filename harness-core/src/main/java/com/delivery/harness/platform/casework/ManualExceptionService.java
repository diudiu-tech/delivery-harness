package com.delivery.harness.platform.casework;

import com.delivery.harness.common.dto.CompensationMethod;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A reviewer can propose an explicit exception, but cannot approve their own proposal. */
@Service
public class ManualExceptionService {
    private final PlatformStore store;
    private final CaseService cases;
    private final BigDecimal ceiling;

    public ManualExceptionService(
            PlatformStore store,
            CaseService cases,
            @Value("${harness.guardrail.max-compensation-amount:50.0}") BigDecimal ceiling) {
        this.store = store;
        this.cases = cases;
        this.ceiling = ceiling;
    }

    public PlatformStore.Entity propose(Principal actor, String caseId, Map<String, Object> input) {
        actor.requireRole("SUPERVISOR");
        cases.require(actor, caseId);
        String reason = CaseService.text(input, "reason"),
                authority = CaseService.text(input, "authorization_basis");
        BigDecimal amount;
        try {
            amount =
                    new BigDecimal(CaseService.text(input, "amount"))
                            .setScale(2, RoundingMode.UNNECESSARY);
        } catch (RuntimeException e) {
            throw PlatformException.invalid("Amount must have at most two decimal places");
        }
        if (amount.signum() < 0 || amount.compareTo(ceiling) > 0)
            throw PlatformException.invalid(
                    "Exception amount exceeds the configured policy ceiling");
        CompensationMethod method;
        try {
            method = CompensationMethod.fromPolicy(CaseService.text(input, "method"));
        } catch (IllegalArgumentException invalid) {
            throw PlatformException.invalid("Unsupported compensation method");
        }
        if (!Set.of(
                        CompensationMethod.REFUND,
                        CompensationMethod.COUPON,
                        CompensationMethod.RED_PACKET,
                        CompensationMethod.RESEND)
                .contains(method)) {
            throw PlatformException.invalid("Unsupported manual exception method");
        }
        if (!method.isMonetary() && amount.signum() != 0) {
            throw PlatformException.invalid("A nonmonetary exception must have a zero amount");
        }
        return store.transact(
                tx -> {
                    PlatformStore.Entity c = tx.require(actor.tenantId(), "case", caseId);
                    CaseService.authorize(actor, c);
                    if (c.version() != CaseService.number(input, "expected_case_version"))
                        throw PlatformException.conflict(
                                "The case changed; review the latest evidence first");
                    if (Set.of("PENDING", "SUBMITTED", "UNKNOWN", "SUCCEEDED")
                            .contains(c.data().get("payment_status")))
                        throw PlatformException.conflict(
                                "An in-flight or completed compensation must be reconciled before"
                                        + " proposing an exception");
                    String snapshotId = CaseService.text(c.data(), "evidence_snapshot_id");
                    if (!snapshotId.equals(input.get("evidence_snapshot_id")))
                        throw PlatformException.conflict(
                                "Exception must bind the current evidence snapshot");
                    PlatformStore.Entity snapshot =
                            tx.require(actor.tenantId(), "order_snapshot", snapshotId);
                    if (method.isCashRefund()) {
                        if (!(snapshot.data().get("order") instanceof Map<?, ?> order)
                                || order.get("refundableBalance") == null) {
                            throw new PlatformException(
                                    422,
                                    "INSUFFICIENT_EVIDENCE",
                                    "An exception refund requires the verified refundable balance");
                        }
                        BigDecimal balance;
                        try {
                            balance = new BigDecimal(order.get("refundableBalance").toString());
                        } catch (RuntimeException invalid) {
                            throw PlatformException.invalid(
                                    "Verified refundable balance is invalid");
                        }
                        if (balance.signum() < 0
                                || balance.stripTrailingZeros().scale() > 2
                                || amount.compareTo(balance) > 0) {
                            throw PlatformException.invalid(
                                    "A refund cannot exceed the verified refundable balance");
                        }
                    }
                    String decisionId = UUID.randomUUID().toString(),
                            reviewId = UUID.randomUUID().toString();
                    Map<String, Object> decision = new LinkedHashMap<>();
                    decision.put("case_id", caseId);
                    decision.put("decision_status", "DETERMINED");
                    decision.put(
                            "amount", Map.of("value", amount.toPlainString(), "currency", "CNY"));
                    decision.put("authoritative_amount", amount);
                    decision.put("currency", "CNY");
                    decision.put("suggested_method", method.name());
                    decision.put("evidence_snapshot_id", snapshotId);
                    decision.put("manual_exception", true);
                    decision.put("created_by", actor.userId());
                    decision.put("reason", reason);
                    decision.put("authorization_basis", authority);
                    decision.put("decided_by", "HUMAN_EXCEPTION");
                    decision.put("policy_version", c.data().get("policy_version"));
                    decision.put("advisory_only", true);
                    PlatformStore.Entity result =
                            tx.create(
                                    actor.tenantId(),
                                    "decision",
                                    decisionId,
                                    decision,
                                    actor.userId(),
                                    "MANUAL_EXCEPTION_PROPOSED");
                    Map<String, Object> review = new LinkedHashMap<>();
                    review.put("case_id", caseId);
                    review.put("decision_id", decisionId);
                    review.put("decision_version", result.version());
                    review.put("evidence_snapshot_id", snapshotId);
                    review.put("status", "PENDING");
                    tx.create(
                            actor.tenantId(),
                            "review",
                            reviewId,
                            review,
                            actor.userId(),
                            "EXCEPTION_REVIEW_CREATED");
                    if (c.data().get("review_id") != null) {
                        PlatformStore.Entity prior =
                                tx.require(
                                        actor.tenantId(),
                                        "review",
                                        c.data().get("review_id").toString());
                        Map<String, Object> old = new LinkedHashMap<>(prior.data());
                        old.put("status", "SUPERSEDED");
                        tx.update(
                                actor.tenantId(),
                                "review",
                                prior.id(),
                                prior.version(),
                                old,
                                actor.userId(),
                                "REVIEW_SUPERSEDED");
                    }
                    Map<String, Object> data = new LinkedHashMap<>(c.data());
                    data.put("current_decision_id", decisionId);
                    data.put("review_id", reviewId);
                    data.put("review_status", "PENDING");
                    data.put("status", "PENDING_REVIEW");
                    data.put("risk_level", "HIGH");
                    tx.update(
                            actor.tenantId(),
                            "case",
                            caseId,
                            c.version(),
                            data,
                            actor.userId(),
                            "CASE_EXCEPTION_PENDING");
                    return result;
                });
    }
}
