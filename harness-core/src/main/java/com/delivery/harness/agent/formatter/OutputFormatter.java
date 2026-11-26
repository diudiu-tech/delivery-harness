package com.delivery.harness.agent.formatter;

import com.delivery.harness.agent.analysis.OrderTimeline;
import com.delivery.harness.common.dto.CompensationMethod;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Separates parseable model diagnostics from adoptable, evidence-validated conclusions.
 *
 * <p>The rule engine already computed the reproducible amount. Passing that number through a
 * sampled model and then parsing it back would weaken its provenance without adding information.
 * Keep the rule decision and model diagnostics in separate fields so an explanation can never
 * silently become the financial source of truth. See {@code
 * docs/adr/0002-rule-engine-owns-the-compensation-amount.md}.
 *
 * <p>Legacy fields remain readable while v2 exposes nullable facts, decision state and blocked
 * drafts. Nothing in a formatted response authorizes payment or establishes responsibility.
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
        return formatAbnormalOrderAnalysis(
                order,
                timeline,
                modelOutput,
                retrievalResult,
                guardrailPassed,
                basicEvidence(order, timeline));
    }

    public Map<String, Object> formatAbnormalOrderAnalysis(
            OrderInfo order,
            OrderTimeline timeline,
            String modelOutput,
            RetrievalService.RetrievalResult retrievalResult,
            boolean guardrailPassed,
            Map<String, Object> evidence) {
        Map<String, Object> analysis = parseModelJson(modelOutput);
        String primaryCause = asText(analysis.get("primary_cause"));
        String confidence = asText(analysis.get("confidence"));
        boolean parsed = isValidAbnormalAnalysis(analysis);
        boolean agreesWithBaseline = agreesWithBaseline(primaryCause, timeline);
        List<String> validationErrors =
                parsed
                        ? EvidenceValidator.validate(analysis, evidence, ruleIds(retrievalResult))
                        : List.of("invalid_output_schema");
        validationErrors = new ArrayList<>(validationErrors);
        if (parsed && timeline.overtime()) {
            validationErrors.add("responsibility_requires_historical_supporting_evidence");
        }
        if (parsed && "ON_TIME".equals(timeline.overtimeStatus()) && !"无异常".equals(primaryCause)) {
            validationErrors.add("on_time_order_cannot_have_delay_attribution");
        }
        if (parsed && "UNKNOWN".equals(causeCode(primaryCause))) {
            validationErrors.add("unsupported_cause_code");
        }
        if (!List.of("ON_TIME", "LATE").contains(timeline.overtimeStatus())) {
            validationErrors.add("order_timing_not_applicable_or_unknown");
        }
        boolean modelAdoptable =
                parsed && validationErrors.isEmpty() && guardrailPassed && timeline.complete();

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
        result.put("schema_version", "2.0");
        result.put("facts", ((Map<?, ?>) evidence.get("order")).get("fields"));
        result.put("derived_facts", timeline.asTrustedMap());
        result.put("hypotheses", parsed && !modelAdoptable ? List.of(analysis) : List.of());
        result.put("reviewer_decision", null);
        result.put("is_abnormal", timeline.overtime());
        result.put("overtime_status", timeline.overtimeStatus());
        result.put(
                "decision_status",
                "NOT_APPLICABLE".equals(timeline.overtimeStatus())
                        ? "OUT_OF_SCOPE"
                        : !timeline.complete() ? "INSUFFICIENT_EVIDENCE" : "DETERMINED");
        result.put("baseline_is_responsibility", false);
        result.put(
                "primary_cause_code",
                modelAdoptable
                        ? causeCode(primaryCause)
                        : "ON_TIME".equals(timeline.overtimeStatus()) ? "NO_ANOMALY" : "UNKNOWN");
        result.put("model_adoptable", modelAdoptable);
        result.put("adopted_model_analysis", modelAdoptable ? analysis : null);
        result.put("validation_errors", validationErrors);
        result.put("customer_message_status", modelAdoptable ? "VALIDATED" : "BLOCKED");
        String verifiedMessage =
                timeline.complete() && "ON_TIME".equals(timeline.overtimeStatus())
                        ? "本次订单实际送达未晚于承诺时间，未识别出配送超时。其他问题仍需人工核查。"
                        : timeline.complete() && "LATE".equals(timeline.overtimeStatus())
                                ? "本次订单存在送达超时，原因与责任仍需结合订单发生时的证据人工核查。"
                                : null;
        addCustomerMessages(
                result, analysis.get("customer_message"), modelAdoptable, verifiedMessage);
        result.put("evidence", evidence);
        result.put("timeline", timeline.asMap());
        result.put("timeline_v2", timeline.asTrustedMap());
        result.put("baseline_cause", timeline.dominantLegLabel());
        result.put("model_analysis", parsed ? analysis : null);
        result.put("model_raw_output", modelOutput);
        result.put("model_output_parsed", parsed);
        result.put("agrees_with_baseline", parsed ? agreesWithBaseline : null);
        result.put("citations", buildCitations(retrievalResult));
        result.put("guardrail_passed", guardrailPassed);
        if (!modelAdoptable && parsed) {
            reviewReasons.add("model_evidence_not_verified");
        }
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
        boolean modelOutputAvailable = modelOutput != null && !modelOutput.isBlank();
        boolean modelEscalates = Boolean.TRUE.equals(justification.get("escalate"));
        boolean ruleRequiresApproval = Boolean.TRUE.equals(ruleDecision.get("approval_required"));
        Map<String, Object> evidence = compensationEvidence(order, timeline, ruleDecision);
        Set<String> applicableRuleIds = new LinkedHashSet<>();
        if (ruleDecision.get("matched_rules") instanceof List<?> rules) {
            for (Object rule : rules) {
                if (rule instanceof Map<?, ?> fields
                        && fields.get("rule_id") instanceof String id) {
                    applicableRuleIds.add(id);
                }
            }
        }
        List<String> evidenceErrors =
                parsed
                        ? EvidenceValidator.validate(justification, evidence, applicableRuleIds)
                        : List.of("invalid_output_schema");
        String confidence = asText(justification.get("confidence"));

        List<String> approvalReasons = new ArrayList<>();
        if (ruleRequiresApproval) {
            approvalReasons.add(
                    asText(ruleDecision.get("approval_reason")).isEmpty()
                            ? "rule_engine_requires_approval"
                            : asText(ruleDecision.get("approval_reason")));
        }
        if (!guardrailPassed) {
            approvalReasons.add("guardrail_failed");
        }
        if (!modelOutputAvailable) {
            approvalReasons.add("model_output_unavailable");
        }
        if (!parsed) {
            approvalReasons.add("model_output_unparseable");
        }
        if (parsed && !evidenceErrors.isEmpty()) {
            approvalReasons.add("model_evidence_not_verified");
        }
        if (parsed && !CONFIDENCE_HIGH.equalsIgnoreCase(confidence)) {
            approvalReasons.add("model_confidence_below_high");
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
        result.put("schema_version", "2.0");
        result.put("facts", ((Map<?, ?>) evidence.get("order")).get("fields"));
        result.put("derived_facts", timeline.asTrustedMap());
        result.put(
                "hypotheses",
                parsed && (!evidenceErrors.isEmpty() || !guardrailPassed)
                        ? List.of(justification)
                        : List.of());
        result.put("reviewer_decision", null);
        result.put("timeline", timeline.asMap());
        result.put("timeline_v2", timeline.asTrustedMap());

        // The payout and its provenance. These come from the rule engine and
        // never from the model; see docs/adr/0002.
        result.put("should_compensate", ruleDecision.get("should_compensate"));
        result.put("suggested_amount", ruleDecision.get("suggested_amount"));
        result.put("suggested_method", ruleDecision.get("suggested_method"));
        result.put("method", ruleDecision.get("method"));
        result.put("matched_rules", ruleDecision.get("matched_rules"));
        result.put("policy_source", ruleDecision.get("policy_source"));
        for (String field :
                List.of(
                        "authoritative_amount",
                        "amount_is_candidate",
                        "decision_status",
                        "decision_reasons",
                        "policy_version",
                        "currency",
                        "calculation",
                        "input_snapshot_hash")) {
            result.put(field, ruleDecision.get(field));
        }
        boolean determined =
                !ruleDecision.containsKey("decision_status")
                        || "DETERMINED".equals(ruleDecision.get("decision_status"));
        result.put("amount_safe", determined && guardrailPassed);
        result.put("language_safe", guardrailPassed);
        result.put(
                "model_adoptable",
                parsed && evidenceErrors.isEmpty() && guardrailPassed && determined);
        result.put(
                "customer_message_status",
                parsed && evidenceErrors.isEmpty() && guardrailPassed && determined
                        ? "VALIDATED"
                        : "BLOCKED");
        addCustomerMessages(
                result,
                justification.get("customer_message"),
                parsed && evidenceErrors.isEmpty() && guardrailPassed && determined,
                compensationMessage(ruleDecision));
        result.put("validation_errors", evidenceErrors);
        result.put("evidence", evidence);
        result.put(
                "needs_human_review",
                !approvalReasons.isEmpty() || !evidenceErrors.isEmpty() || !determined);
        result.put("amount_decided_by", "rule_engine");

        result.put("model_justification", parsed ? justification : null);
        result.put("model_raw_output", modelOutput);
        result.put("model_output_available", modelOutputAvailable);
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
     * Extracts the model's JSON object, tolerating fenced blocks and surrounding prose. Returns an
     * empty map when nothing parses; callers surface that as {@code model_output_parsed: false}
     * rather than failing the request, because the deterministic parts of the answer are still
     * useful without it.
     */
    private Map<String, Object> parseModelJson(String modelOutput) {
        if (modelOutput == null || modelOutput.length() > 50_000) {
            return Collections.emptyMap();
        }
        String json = outputParser.extractJson(modelOutput);
        if (json == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed =
                    JsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (RuntimeException e) {
            log.warn("Model output could not be deserialised: errorType={}", e.getClass().getSimpleName());
            return Collections.emptyMap();
        }
    }

    private static boolean isValidAbnormalAnalysis(Map<String, Object> analysis) {
        return isNonBlankText(analysis.get("primary_cause"))
                && isList(analysis.get("secondary_causes"))
                && isEvidenceList(analysis.get("evidence_chain"))
                && isList(analysis.get("applicable_rules"))
                && isList(analysis.get("suggested_actions"))
                && isList(analysis.get("risk_notes"))
                && isValidConfidence(analysis.get("confidence"));
    }

    private static boolean isValidCompensationJustification(Map<String, Object> justification) {
        return isNonBlankText(justification.get("reason"))
                && isNonBlankText(justification.get("customer_message"))
                && isEvidenceList(justification.get("evidence_chain"))
                && isList(justification.get("applicable_rules"))
                && isList(justification.get("risk_warnings"))
                && justification.get("escalate") instanceof Boolean
                && isValidConfidence(justification.get("confidence"));
    }

    private static boolean isNonBlankText(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= 2000;
    }

    private static boolean isList(Object value) {
        return value instanceof List<?> list
                && list.size() <= 32
                && list.stream().allMatch(OutputFormatter::isNonBlankText);
    }

    private static boolean isEvidenceList(Object value) {
        return value instanceof List<?> list
                && !list.isEmpty()
                && list.size() <= 32
                && list.stream()
                        .allMatch(
                                item ->
                                        item instanceof Map<?, ?> map
                                                && EvidenceValidator.isReference(map));
    }

    /**
     * Model prose is a reviewable draft; only this server-authored template is a verified customer
     * message.
     */
    private static void addCustomerMessages(
            Map<String, Object> result,
            Object modelMessage,
            boolean modelValid,
            String verifiedMessage) {
        result.put("model_customer_message", modelMessage instanceof String ? modelMessage : null);
        result.put("model_customer_message_status", modelValid ? "DRAFT" : "BLOCKED");
        result.put("verified_customer_message", verifiedMessage);
        result.put(
                "verified_customer_message_status",
                verifiedMessage == null ? "BLOCKED" : "VALIDATED");
        result.put("customer_message", verifiedMessage);
        result.put("customer_message_source", "DETERMINISTIC_TEMPLATE");
    }

    private static String compensationMessage(Map<String, Object> decision) {
        if (!"DETERMINED".equals(decision.get("decision_status"))
                || decision.get("authoritative_amount") == null
                || !"CNY".equals(decision.get("currency"))) {
            return null;
        }
        BigDecimal amount;
        try {
            amount =
                    new BigDecimal(decision.get("authoritative_amount").toString())
                            .setScale(2, RoundingMode.UNNECESSARY);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (amount.signum() < 0) {
            return null;
        }
        String method = asText(decision.get("method"));
        if (method.isBlank()) {
            method = asText(decision.get("suggested_method"));
        }
        CompensationMethod compensationMethod;
        try {
            compensationMethod = CompensationMethod.fromPolicy(method);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
        if (compensationMethod == CompensationMethod.MANUAL) {
            return null;
        }
        String label = compensationMethod.displayName();
        if (amount.signum() == 0 && !"补送".equals(label)) {
            return "根据本次订单事实与规则，本次未形成赔付建议。如需进一步核查，请补充材料并由人工复核。";
        }
        return "根据本次订单事实与规则，当前建议为"
                + label
                + "，金额为 CNY "
                + amount.toPlainString()
                + "。该建议须经人工审核确认，尚未执行。";
    }

    private static boolean isValidConfidence(Object value) {
        if (!(value instanceof String confidence)) {
            return false;
        }
        return "HIGH".equalsIgnoreCase(confidence)
                || "MEDIUM".equalsIgnoreCase(confidence)
                || "LOW".equalsIgnoreCase(confidence);
    }

    /** An exact baseline comparison is a review signal, never a responsibility judgment. */
    private static boolean agreesWithBaseline(String primaryCause, OrderTimeline timeline) {
        if (primaryCause == null || primaryCause.isBlank()) {
            return false;
        }
        String baseline = timeline.dominantLegLabel();
        return primaryCause.equals(baseline);
    }

    public static Map<String, Object> basicEvidence(OrderInfo order, OrderTimeline timeline) {
        Map<String, Object> safeOrder = new LinkedHashMap<>();
        safeOrder.put("order_id", order.getOrderId());
        safeOrder.put("status", order.getStatus());
        safeOrder.put("order_amount", order.getOrderAmount());
        safeOrder.put("refundable_balance", order.getRefundableBalance());
        safeOrder.put("station_id", order.getStationId());
        safeOrder.put("city_code", order.getCityCode());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("order", Map.of("version", "1", "fields", safeOrder));
        evidence.put("timeline", Map.of("version", "1", "fields", timeline.asMap()));
        evidence.put("timeline_v2", Map.of("version", "2", "fields", timeline.asTrustedMap()));
        return evidence;
    }

    public static Map<String, Object> compensationEvidence(
            OrderInfo order, OrderTimeline timeline, Map<String, Object> decision) {
        Map<String, Object> evidence = new LinkedHashMap<>(basicEvidence(order, timeline));
        evidence.put("decision", Map.of("version", "1", "fields", decision));
        return evidence;
    }

    private static Set<String> ruleIds(RetrievalService.RetrievalResult retrieval) {
        Set<String> ids = new LinkedHashSet<>();
        if (retrieval != null && retrieval.getItems() != null) {
            retrieval.getItems().stream()
                    .filter(i -> "rule".equals(i.getSourceType()))
                    .forEach(i -> ids.add(i.getSourceId()));
        }
        return ids;
    }

    private static String causeCode(String cause) {
        return switch (cause) {
            case "商家出餐慢" -> "MERCHANT_PREP_DELAY";
            case "运力不足" -> "CAPACITY_SHORTAGE";
            case "路途耗时长" -> "ROAD_DELAY";
            case "天气异常" -> "WEATHER_RELATED";
            case "骑手到店慢" -> "TO_SHOP_DELAY";
            case "无异常" -> "NO_ANOMALY";
            default -> "UNKNOWN";
        };
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
            citation.put("version_id", item.getVersionId());
            citation.put("citation_id", item.getCitationId());
            citation.put("content_hash", item.getContentHash());
            citations.add(citation);
        }
        return citations;
    }
}
