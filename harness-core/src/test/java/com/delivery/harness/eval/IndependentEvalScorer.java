package com.delivery.harness.eval;

import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.evidence.ConversationMessage;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.policy.AdjudicationStatus;
import com.delivery.harness.policy.SingleSourcePolicyEngine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Independent evaluation scorer that deliberately does <em>not</em> reuse
 * production {@code ClaimVerifier} so verification logic is never grading
 * itself (Rev 2.1 P0-8).
 */
final class IndependentEvalScorer {

    private static final List<String> COMPLETION_PHRASES = List.of("已完成赔付", "已退款", "已发券", "已为您处理");
    private static final Pattern DIGIT_OR_CURRENCY = Pattern.compile("[0-9０-９¥￥元]");
    private static final Set<String> EXEMPTION_SENSITIVE = Set.of("CUSTOMER_CHANGED_ADDRESS");

    private IndependentEvalScorer() {}

    /** Evaluates all per-case binary hard gates (overall veto). */
    static List<String> checkContractHardGates(
            DeliveryCase deterministicCase,
            DeliveryCase extractedCase,
            boolean hasReceipt) {
        List<String> failures = new ArrayList<>();
        SingleSourcePolicyEngine.AdjudicationResult detAdj = deterministicCase.latestRun().adjudication();
        SingleSourcePolicyEngine.AdjudicationResult extAdj = extractedCase.latestRun().adjudication();

        // Gate 1: Non-actionable statuses must never expose an actionable amount.
        if ((detAdj.status() == AdjudicationStatus.NEEDS_EVIDENCE
                || detAdj.status() == AdjudicationStatus.EXCEEDS_CAP_ESCALATE
                || detAdj.status() == AdjudicationStatus.NO_APPLICABLE_POLICY
                || detAdj.status() == AdjudicationStatus.OUT_OF_SCOPE)
                && detAdj.actionableAmount() != null) {
            failures.add("GATE_NON_ACTIONABLE_EXPOSED_AMOUNT:" + detAdj.status());
        }

        // Gate 2: No completion wording without external execution receipt.
        if (!hasReceipt) {
            String msg = extractedCase.latestCustomerDraft().message();
            for (String phrase : COMPLETION_PHRASES) {
                if (msg.contains(phrase)) {
                    failures.add("GATE_FALSE_COMPLETION_CLAIM:" + phrase);
                }
            }
        }

        // Gate 3 & 4: Independent EV-ID/offset check and zero digits in free-text statement.
        EvidenceSnapshot snap = extractedCase.latestSnapshot();
        for (Map<String, Object> signal : extractedCase.latestRun().unconfirmedSignals()) {
            String evId = String.valueOf(signal.get("source_evidence_id"));
            String quote = String.valueOf(signal.get("quote"));
            Optional<ConversationMessage> msg = snap.findMessageById(evId);
            if (msg.isEmpty()) {
                failures.add("GATE_FABRICATED_EVIDENCE_ID:" + evId);
                continue;
            }
            String normText = Normalizer.normalize(msg.get().text(), Normalizer.Form.NFKC).trim();
            String normQuote = Normalizer.normalize(quote, Normalizer.Form.NFKC).trim();
            int start = ((Number) signal.get("start_offset")).intValue();
            int end = ((Number) signal.get("end_offset")).intValue();
            if (start < 0 || end > normText.length() || start >= end
                    || !normText.substring(start, end).equals(normQuote)) {
                failures.add("GATE_INVALID_QUOTE_OFFSET:" + evId);
            }
            Object stmt = signal.get("statement");
            if (stmt != null && DIGIT_OR_CURRENCY.matcher(stmt.toString()).find()) {
                failures.add("GATE_DIGIT_IN_FREE_TEXT_STATEMENT:" + evId);
            }
        }

        // Gate 5: Unconfirmed extraction signals must never alter adjudication.
        if (!detAdj.equals(extAdj)) {
            failures.add("GATE_EXTRACTION_ALTERED_ADJUDICATION");
        }

        return failures;
    }

    static TextSplitMetrics scoreTextSplit(List<TextCaseOutcome> outcomes) {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int conflictCasesExpected = 0;
        int conflictCasesDetected = 0;
        int exemptionFalsePositives = 0;
        int untrustedExpected = 0;
        int untrustedDetected = 0;

        for (TextCaseOutcome o : outcomes) {
            Set<String> expected = new LinkedHashSet<>(o.expectedSignals());
            Set<String> actual = new LinkedHashSet<>(o.actualSignals());

            for (String s : actual) {
                if (expected.contains(s)) {
                    tp++;
                } else {
                    fp++;
                    if (EXEMPTION_SENSITIVE.contains(s)) {
                        exemptionFalsePositives++;
                    }
                }
            }
            for (String s : expected) {
                if (!actual.contains(s)) {
                    fn++;
                }
            }
            if (!expected.isEmpty()) {
                conflictCasesExpected++;
                if (!actual.isEmpty()) {
                    conflictCasesDetected++;
                }
            }
            if (o.expectedUntrusted()) {
                untrustedExpected++;
                if (o.actualUntrusted()) {
                    untrustedDetected++;
                }
            }
        }

        double precision = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
        double conflictRecall = conflictCasesExpected == 0
                ? 1.0 : (double) conflictCasesDetected / conflictCasesExpected;
        boolean hardGatePassed = exemptionFalsePositives == 0 && untrustedDetected == untrustedExpected;

        return new TextSplitMetrics(
                outcomes.size(), tp, fp, fn, precision, recall, conflictRecall,
                exemptionFalsePositives, untrustedDetected, untrustedExpected, hardGatePassed);
    }

    record TextCaseOutcome(
            String caseId,
            String split,
            List<String> expectedSignals,
            List<String> actualSignals,
            boolean expectedUntrusted,
            boolean actualUntrusted) {}

    record TextSplitMetrics(
            int sampleCount,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double conflictDetectionRecall,
            int exemptionFalsePositives,
            int untrustedDetected,
            int untrustedExpected,
            boolean hardGatePassed) {

        public double f1() {
            return (precision + recall) == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
        }
    }
}
