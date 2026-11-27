package com.delivery.harness.template;

import com.delivery.harness.policy.AdjudicationStatus;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure deterministic customer message template renderer replacing LLM-generated
 * customer drafts (P1 / ADR-0011).
 *
 * <p>Guarantees structurally that:
 * <ul>
 *   <li>completed execution wording ("已完成赔付处理") is rendered <em>only</em>
 *       when an external execution receipt has been recorded</li>
 *   <li>non-actionable statuses ({@code NEEDS_EVIDENCE},
 *       {@code EXCEEDS_CAP_ESCALATE}, {@code NO_APPLICABLE_POLICY},
 *       {@code OUT_OF_SCOPE}) never display currency amounts prior to human
 *       approval and external execution</li>
 *   <li>forbidden promise phrases cannot appear in any output</li>
 * </ul>
 */
@Service
public class DeterministicCustomerTemplateRenderer {

    private static final String DISCLAIMER = "本草稿由确定性模板生成，未经人工确认不得自动发送。";

    public CustomerDraft render(
            SingleSourcePolicyEngine.AdjudicationResult adjudication,
            boolean hasExecutionReceipt,
            String externalReceiptId) {
        Objects.requireNonNull(adjudication, "adjudication must not be null");
        AdjudicationStatus status = adjudication.status();

        if (hasExecutionReceipt && externalReceiptId != null && !externalReceiptId.isBlank()) {
            BigDecimal amount = adjudication.actionableAmount() != null
                    ? adjudication.actionableAmount()
                    : adjudication.calculatedAmount();
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                return renderExecuted(
                        amount,
                        adjudication.suggestedMethod(),
                        adjudication.matchedRuleId(),
                        externalReceiptId);
            }
        }

        if (status == AdjudicationStatus.ELIGIBLE) {
            BigDecimal amount = adjudication.actionableAmount();
            String amountText = amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
            String method = normalizeOrDefault(adjudication.suggestedMethod(), "按规则补偿");
            String ruleId = normalizeOrDefault(adjudication.matchedRuleId(), "现行政策");

            String msg = "非常抱歉本次配送给您带来不便。根据平台规则（" + ruleId + "），当前建议方案为："
                    + method + " " + amountText + " 元。该方案尚待人工审核确认，确认后将按流程为您办理。";
            return new CustomerDraft("TPL-ELIGIBLE-PENDING", false, false, msg, DISCLAIMER);
        }

        return switch (status) {
            case INELIGIBLE -> new CustomerDraft(
                    "TPL-INELIGIBLE",
                    false,
                    false,
                    "您好，经核对订单记录与平台规则，本单实际履约情况未触发赔付条件（如实际送达未超过承诺时间）。如对配送体验有其他疑问，欢迎随时告知，我们将安排人工进一步跟进。",
                    DISCLAIMER);
            case NEEDS_EVIDENCE -> {
                String missing = adjudication.missingEvidence().isEmpty()
                        ? "关键事实信息"
                        : String.join("、", adjudication.missingEvidence());
                yield new CustomerDraft(
                        "TPL-NEEDS-EVIDENCE",
                        false,
                        false,
                        "您好，您的反馈我们已收到。当前尚需补充或核实关键信息（待核实项：" + missing
                                + "），核实完成后将第一时间向您同步处理方案。",
                        DISCLAIMER);
            }
            case EXCEEDS_CAP_ESCALATE -> new CustomerDraft(
                    "TPL-EXCEEDS-CAP-ESCALATE",
                    false,
                    false,
                    "您好，您的反馈我们非常重视。本单涉及较高金额赔付评估，已按流程升级至主管进一步审核，审核完成后将由专人联系您确认方案。",
                    DISCLAIMER);
            case NO_APPLICABLE_POLICY -> new CustomerDraft(
                    "TPL-NO-POLICY",
                    false,
                    false,
                    "您好，您的反馈已登记。当前诉求暂不在自动定档政策覆盖范围内，已转由客服专员人工核实处理，请您耐心等待进一步联系。",
                    DISCLAIMER);
            case OUT_OF_SCOPE -> new CustomerDraft(
                    "TPL-OUT-OF-SCOPE",
                    false,
                    false,
                    "您好，监测到该订单尚未完成送达，建议待订单完成后再评估相关赔付；如有紧急配送问题，请联系在线客服协助处理。",
                    DISCLAIMER);
            case ELIGIBLE -> throw new IllegalStateException("Unreachable");
        };
    }

    /**
     * Renders the post-execution customer notification using the actual approved
     * payout amount and method (covering standard rule payouts, supervisor
     * over-cap approvals, and human overrides) once an external receipt exists.
     */
    public CustomerDraft renderExecuted(
            BigDecimal executedAmount,
            String executedMethod,
            String basisReference,
            String externalReceiptId) {
        Objects.requireNonNull(executedAmount, "executedAmount must not be null");
        if (externalReceiptId == null || externalReceiptId.isBlank()) {
            throw new IllegalArgumentException("externalReceiptId is required to render executed draft");
        }
        String amountText = executedAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String method = normalizeOrDefault(executedMethod, "按审批方案补偿");
        String basis = normalizeOrDefault(basisReference, "人工复核审批");

        String msg = "非常抱歉本次配送给您带来不便。按平台规则与复核结论（" + basis + "）已完成赔付处理："
                + method + " " + amountText + " 元（外部回执号：" + externalReceiptId.trim() + "），请注意查收。";
        return new CustomerDraft("TPL-EXECUTED-WITH-RECEIPT", true, false, msg, DISCLAIMER);
    }

    private static String normalizeOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
