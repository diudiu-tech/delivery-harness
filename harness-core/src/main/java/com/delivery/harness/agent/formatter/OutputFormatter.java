package com.delivery.harness.agent.formatter;

import com.delivery.harness.agent.analysis.OrderTimeline;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the response body for each workflow.
 *
 * <p>Two things changed here relative to the first revision.
 *
 * <p>First, the model's raw text is no longer the only output. It is parsed
 * into {@code model_analysis} where possible and kept alongside as
 * {@code model_raw_output}, so a consumer can read structured fields without
 * re-implementing JSON extraction, and a failure to parse is visible rather
 * than silent.
 *
 * <p>Second, {@code needs_human_review} and {@code approval_required} were
 * hard-coded to {@code true}. A constant cannot be wrong, but it also cannot
 * be informative: it meant the system could never reduce human load and could
 * never report which cases were actually risky. They are now derived from
 * signals that vary — guardrail outcome, whether the model's output parsed,
 * its stated confidence, whether it agreed with the deterministic baseline,
 * and the rule engine's own approval threshold.
 *
 * <p>This is a change in reporting, not in authority. Nothing here executes a
 * payment, and a review is still requested whenever any signal is unfavourable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputFormatter {

    private static final String CONFIDENCE_HIGH = "HIGH";

    private final StructuredOutputParser outputParser;

    public Map<String, Object> formatAbnormalOrderAnalysis(
            OrderInfo order,
            OrderTimeline timeline,
            String modelOutput,
            RetrievalService.RetrievalResult retrievalResult,
            boolean guardrailPassed) {

        Map<String, Object> analysis = parseModelJson(modelOutput);
        String primaryCause = asText(analysis.get("primary_cause"));
        String confidence = asText(analysis.get("confidence"));
        boolean parsed = isValidAbnormalAnalysis(analysis);
        boolean agreesWithBaseline = agreesWithBaseline(primaryCause, timeline);

        List<String> reviewReasons = new ArrayList<>();
        if (!guardrailPassed) {
            reviewReasons.add("guardrail_failed");
        }
        if (!parsed) {
            reviewReasons.add("model_output_unparseable");
        }
        if (parsed && !CONFIDENCE_HIGH.equalsIgnoreCase(confidence)) {
            reviewReasons.add("model_confidence_below_high");
        }
        if (parsed && !agreesWithBaseline) {
            reviewReasons.add("disagrees_with_deterministic_baseline");
        }
        if (!timeline.complete()) {
            reviewReasons.add("incomplete_order_timeline");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getOrderId());
        result.put("analysis_type", "abnormal_order");
        result.put("is_abnormal", timeline.overtime());
        result.put("timeline", timeline.asMap());
        result.put("baseline_cause", timeline.dominantLegLabel());
        result.put("model_analysis", parsed ? analysis : null);
        result.put("model_raw_output", modelOutput);
        result.put("model_output_parsed", parsed);
        result.put("agrees_with_baseline", parsed ? agreesWithBaseline : null);
        result.put("citations", buildCitations(retrievalResult));
        result.put("guardrail_passed", guardrailPassed);
        result.put("needs_human_review", !reviewReasons.isEmpty());
        result.put("review_reasons", reviewReasons);
        result.put("advisory_only", true);
        result.put("generated_at", Instant.now().toString());
        return result;
    }

    public Map<String, Object> formatCompensationSuggestion(
            OrderInfo order,
            String complaintType,
            OrderTimeline timeline,
            Map<String, Object> ruleDecision,
            String modelOutput,
            RetrievalService.RetrievalResult retrievalResult,
            boolean guardrailPassed) {

        Map<String, Object> justification = parseModelJson(modelOutput);
        boolean parsed = isValidCompensationJustification(justification);
        boolean modelEscalates = Boolean.TRUE.equals(justification.get("escalate"));
        boolean ruleRequiresApproval = Boolean.TRUE.equals(ruleDecision.get("approval_required"));

        List<String> approvalReasons = new ArrayList<>();
        if (ruleRequiresApproval) {
            approvalReasons.add(asText(ruleDecision.get("approval_reason")).isEmpty()
                    ? "rule_engine_requires_approval"
                    : asText(ruleDecision.get("approval_reason")));
        }
        if (!guardrailPassed) {
            approvalReasons.add("guardrail_failed");
        }
        if (!parsed) {
            approvalReasons.add("model_output_unparseable");
        }
        if (modelEscalates) {
            approvalReasons.add("model_requested_escalation");
        }
        if (!timeline.complete()) {
            approvalReasons.add("incomplete_order_timeline");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getOrderId());
        result.put("complaint_type", complaintType);
        result.put("analysis_type", "compensation_suggestion");
        result.put("timeline", timeline.asMap());

        // The payout and its provenance. These come from the rule engine and
        // never from the model; see docs/adr/0002.
        result.put("should_compensate", ruleDecision.get("should_compensate"));
        result.put("suggested_amount", ruleDecision.get("suggested_amount"));
        result.put("suggested_method", ruleDecision.get("suggested_method"));
        result.put("matched_rules", ruleDecision.get("matched_rules"));
        result.put("amount_decided_by", "rule_engine");

        result.put("model_justification", parsed ? justification : null);
        result.put("model_raw_output", modelOutput);
        result.put("model_output_parsed", parsed);
        result.put("citations", buildCitations(retrievalResult));
        result.put("guardrail_passed", guardrailPassed);
        result.put("approval_required", !approvalReasons.isEmpty());
        result.put("approval_reasons", approvalReasons);
        result.put("advisory_only", true);
        result.put("generated_at", Instant.now().toString());
        return result;
    }

    /**
     * Extracts the model's JSON object, tolerating fenced blocks and
     * surrounding prose. Returns an empty map when nothing parses; callers
     * surface that as {@code model_output_parsed: false} rather than failing
     * the request, because the deterministic parts of the answer are still
     * useful without it.
     */
    private Map<String, Object> parseModelJson(String modelOutput) {
        String json = outputParser.extractJson(modelOutput);
        if (json == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed =
                    JsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (RuntimeException e) {
            log.warn("Model output looked like JSON but did not deserialise: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static boolean isValidAbnormalAnalysis(Map<String, Object> analysis) {
        return isNonBlankText(analysis.get("primary_cause"))
                && isList(analysis.get("secondary_causes"))
                && isList(analysis.get("evidence_chain"))
                && isList(analysis.get("applicable_rules"))
                && isList(analysis.get("suggested_actions"))
                && isList(analysis.get("risk_notes"))
                && isValidConfidence(analysis.get("confidence"));
    }

    private static boolean isValidCompensationJustification(Map<String, Object> justification) {
        return isNonBlankText(justification.get("reason"))
                && isNonBlankText(justification.get("customer_message"))
                && isList(justification.get("risk_warnings"))
                && justification.get("escalate") instanceof Boolean
                && isValidConfidence(justification.get("confidence"));
    }

    private static boolean isNonBlankText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean isList(Object value) {
        return value instanceof List<?>;
    }

    private static boolean isValidConfidence(Object value) {
        if (!(value instanceof String confidence)) {
            return false;
        }
        return "HIGH".equalsIgnoreCase(confidence)
                || "MEDIUM".equalsIgnoreCase(confidence)
                || "LOW".equalsIgnoreCase(confidence);
    }

    /**
     * Whether the model's stated cause is consistent with the longest leg.
     *
     * <p>Substring containment in both directions, which is deliberately loose:
     * this drives a review flag, not a score. Disagreement is not evidence the
     * model is wrong — the baseline is naive — but it is a good reason for a
     * person to look.
     */
    private static boolean agreesWithBaseline(String primaryCause, OrderTimeline timeline) {
        if (primaryCause == null || primaryCause.isBlank()) {
            return false;
        }
        String baseline = timeline.dominantLegLabel();
        return primaryCause.contains(baseline) || baseline.contains(primaryCause);
    }

    private static String asText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<Map<String, Object>> buildCitations(
            RetrievalService.RetrievalResult retrievalResult) {
        if (retrievalResult == null || retrievalResult.getItems() == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> citations = new ArrayList<>();
        List<RetrievalService.RetrievalItem> items = retrievalResult.getItems();
        for (int i = 0; i < items.size(); i++) {
            RetrievalService.RetrievalItem item = items.get(i);
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("index", i + 1);
            citation.put("source_type", item.getSourceType());
            citation.put("source_id", item.getSourceId());
            citation.put("title", item.getTitle());
            citation.put("score", item.getScore());
            citations.add(citation);
        }
        return citations;
    }
}
