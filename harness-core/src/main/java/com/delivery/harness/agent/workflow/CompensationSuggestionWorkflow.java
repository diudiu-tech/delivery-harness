package com.delivery.harness.agent.workflow;

import com.delivery.harness.agent.analysis.OrderTimeline;
import com.delivery.harness.agent.formatter.OutputFormatter;
import com.delivery.harness.agent.guardrail.GuardrailChecker;
import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.ToolException;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.llm.gateway.LlmChatRequest;
import com.delivery.harness.llm.gateway.LlmChatResponse;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.gateway.LlmMessage;
import com.delivery.harness.runtime.RuntimeBinding;
import com.delivery.harness.tool.gateway.ToolGateway;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fixed workflow: propose compensation for a complaint.
 *
 * <p>The amount is decided by the rule engine, not by the model. See {@code
 * docs/adr/0002-rule-engine-owns-the-compensation-amount.md}.
 *
 * <p>Previously the rule tool computed an amount, that amount was placed in the prompt, the model
 * was asked to produce an amount of its own, and a regular expression then checked the model's
 * number against a cap. The money decision travelled through a sampled token sequence for no
 * benefit: the deterministic answer already existed one step earlier.
 *
 * <p>Now the model is asked only for what a lookup table cannot supply — a justification the
 * reviewer can read, risk flags, and whether the case looks unusual enough to escalate. The
 * guardrail validates the rule engine's amount, which is the number that actually reaches the
 * response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationSuggestionWorkflow {

    private static final String SYSTEM_PROMPT =
            "你是即时配送行业的赔付审核助手。\n"
                    + "赔付金额已由规则引擎确定，你不得修改、不得给出你自己的金额。\n"
                    + "你的任务是：解释这笔赔付的依据、指出风险、判断是否需要升级人工复核。\n"
                    + "规则：\n"
                    + "1. 不要输出 suggested_amount 字段，也不要在文字中另行提出金额。\n"
                    + "2. 不要使用「我们承诺」「一定赔偿」「保证赔偿」「必须全额退款」等承诺性表述。\n"
                    + "3. 若证据不足以支持该赔付档位，在 risk_warnings 中说明并将 escalate 置为 true。\n"
                    + "4. 仅输出一个 JSON 对象，不要输出其他文字。";

    private final WorkflowEngine workflowEngine;
    private final ToolGateway toolGateway;
    private final RetrievalService retrievalService;
    private final LlmGateway llmGateway;
    private final OutputFormatter outputFormatter;
    private final GuardrailChecker guardrailChecker;

    @Value("${harness.execution.deterministic-only:false}")
    private boolean deterministicOnly;

    @PostConstruct
    public void init() {
        workflowEngine.registerHandler(HarnessConstants.SCENARIO_COMPENSATION, this::handle);
    }

    private Map<String, Object> handle(Map<String, Object> input, WorkflowExecution execution) {
        String orderId = String.valueOf(input.get("order_id"));
        String complaintType = String.valueOf(input.getOrDefault("complaint_type", "OVERTIME"));
        String damageLevel =
                input.get("damage_level") == null
                        ? null
                        : String.valueOf(input.get("damage_level"));
        StepRecorder steps = new StepRecorder(execution);

        // Step 1: the order.
        OrderInfo boundOrder = RuntimeBinding.evidenceOrder();
        ToolResult orderResult;
        if (boundOrder != null) {
            if (!orderId.equals(boundOrder.getOrderId())) {
                throw new ToolException(
                        HarnessConstants.TOOL_ORDER_QUERY, "Snapshot order mismatch");
            }
            orderResult =
                    ToolResult.builder()
                            .toolName(HarnessConstants.TOOL_ORDER_QUERY)
                            .success(true)
                            .data(boundOrder)
                            .build();
            steps.record(
                    HarnessConstants.STEP_TOOL_CALL,
                    "读取冻结订单快照",
                    HarnessConstants.TOOL_ORDER_QUERY,
                    HarnessConstants.STATUS_SUCCESS,
                    Map.of("order_id", orderId),
                    Map.of("source", "trusted_snapshot", "required", true),
                    0,
                    null);
        } else {
            orderResult =
                    invokeTool(
                            steps,
                            HarnessConstants.TOOL_ORDER_QUERY,
                            Collections.singletonMap("order_id", orderId),
                            "查询订单详情");
        }
        OrderInfo order = WorkflowSupport.readOrder(orderResult, orderId, steps);

        // Step 2: overtime measured from the order, not assumed. The previous
        // revision hard-coded 10 minutes, which pinned every request to the
        // lowest compensation tier and made the top two tiers unreachable.
        long timelineStart = System.nanoTime();
        OrderTimeline timeline = OrderTimeline.of(order);
        steps.record(
                HarnessConstants.STEP_ANALYSIS,
                "时间轴拆解",
                HarnessConstants.STATUS_SUCCESS,
                Collections.singletonMap("order_id", orderId),
                timeline.asMap(),
                StepRecorder.elapsedMs(timelineStart));

        // Step 3: the authoritative decision.
        Map<String, Object> ruleParams = new LinkedHashMap<>();
        ruleParams.put("complaint_type", complaintType);
        ruleParams.put(
                "overtime_minutes",
                timeline.overtimeSeconds() == null ? null : timeline.overtimeMinutes());
        ruleParams.put("overtime_seconds", timeline.overtimeSeconds());
        ruleParams.put("overtime_status", timeline.overtimeStatus());
        ruleParams.put("timeline_quality", timeline.qualityStatus());
        ruleParams.put("damage_evidence_verified", RuntimeBinding.damageEvidenceVerified());
        if (order.getExpectedDeliveryTime() != null) {
            ruleParams.put(
                    "policy_at",
                    order.getExpectedDeliveryTime()
                            .atZone(ZoneId.of("Asia/Shanghai"))
                            .toInstant()
                            .toString());
        }
        ruleParams.put("order_amount", order.getOrderAmount());
        ruleParams.put("refundable_balance", order.getRefundableBalance());
        if (damageLevel != null && !damageLevel.isBlank()) {
            ruleParams.put("damage_level", damageLevel);
        }
        ToolResult ruleResult =
                invokeTool(steps, HarnessConstants.TOOL_COMPENSATION_RULE, ruleParams, "匹配赔付规则");
        if (!Boolean.TRUE.equals(ruleResult.getSuccess())) {
            throw new ToolException(
                    HarnessConstants.TOOL_COMPENSATION_RULE,
                    ruleResult.getErrorMessage() == null
                            ? "rule matching failed"
                            : ruleResult.getErrorMessage());
        }
        Map<String, Object> decision = JsonUtil.toMap(ruleResult.getData());
        BigDecimal authoritativeAmount = toAmount(decision.get("suggested_amount"));

        // Step 4: similar cases, keyed on the complaint and the matched tier.
        String query = buildRetrievalQuery(complaintType, decision, timeline);
        long retrievalStart = System.nanoTime();
        RetrievalService.RetrievalResult retrievalResult;
        boolean retrievalFailed = false;
        try {
            retrievalResult =
                    retrievalService.retrieve(
                            RetrievalService.RetrievalRequest.builder()
                                    .query(query)
                                    .scenario(HarnessConstants.SCENARIO_COMPENSATION)
                                    .topK(3)
                                    .build());
        } catch (RuntimeException e) {
            retrievalFailed = true;
            retrievalResult =
                    RetrievalService.RetrievalResult.builder()
                            .query(query)
                            .items(Collections.emptyList())
                            .totalFound(0)
                            .build();
        }
        Map<String, Object> retrievalOutput = new LinkedHashMap<>();
        retrievalOutput.put("total_found", retrievalResult.getTotalFound());
        retrievalOutput.put("required", false);
        retrievalOutput.put(
                "availability",
                retrievalFailed
                        ? "UNAVAILABLE"
                        : retrievalResult.getItems().isEmpty() ? "NO_MATCH" : "AVAILABLE");
        retrievalOutput.put(
                "source_ids",
                retrievalResult.getItems().stream()
                        .map(RetrievalService.RetrievalItem::getSourceId)
                        .collect(Collectors.toList()));
        steps.record(
                HarnessConstants.STEP_RETRIEVAL,
                "检索相似案例",
                retrievalFailed ? HarnessConstants.STATUS_FAILED : HarnessConstants.STATUS_SUCCESS,
                Collections.singletonMap("query", query),
                retrievalOutput,
                StepRecorder.elapsedMs(retrievalStart));

        // Step 5: the model explains; it does not decide.
        String prompt =
                buildJustificationPrompt(order, timeline, complaintType, decision, retrievalResult);
        long llmStart = System.nanoTime();
        LlmChatResponse llmResponse;
        boolean modelDisabled = deterministicOnly || RuntimeBinding.deterministicOnly();
        boolean degraded =
                retrievalFailed
                        || !timeline.complete()
                        || !"DETERMINED".equals(decision.get("decision_status"));
        try {
            llmResponse =
                    modelDisabled
                            ? LlmChatResponse.builder().success(true).build()
                            : llmGateway.chat(
                                    LlmChatRequest.builder()
                                            .scenario(HarnessConstants.SCENARIO_COMPENSATION)
                                            .messages(
                                                    Arrays.asList(
                                                            LlmMessage.system(SYSTEM_PROMPT),
                                                            LlmMessage.user(prompt)))
                                            .build());
        } catch (RuntimeException e) {
            // The rule engine remains authoritative when the model is down.
            // Return the deterministic payout with an explicit failed model
            // step so callers can distinguish degraded mode from a clean run.
            log.warn(
                    "Compensation justification unavailable; continuing with rule decision:"
                            + " model={}, errorType={}",
                    "configured",
                    e.getClass().getSimpleName());
            degraded = true;
            llmResponse =
                    LlmChatResponse.builder()
                            .model("configured")
                            .success(false)
                            .errorMessage("LLM unavailable")
                            .build();
        }
        Map<String, Object> llmOutput = new LinkedHashMap<>();
        llmOutput.put("model", llmResponse.getModel());
        llmOutput.put("prompt_chars", prompt.length());
        llmOutput.put(
                "response_chars",
                llmResponse.getContent() == null ? 0 : llmResponse.getContent().length());
        llmOutput.put("error", llmResponse.getErrorMessage());
        steps.record(
                HarnessConstants.STEP_LLM_CALL,
                "LLM生成赔付说明",
                null,
                modelDisabled
                        ? "SKIPPED"
                        : Boolean.TRUE.equals(llmResponse.getSuccess())
                                ? HarnessConstants.STATUS_SUCCESS
                                : HarnessConstants.STATUS_FAILED,
                Collections.singletonMap("scenario", HarnessConstants.SCENARIO_COMPENSATION),
                llmOutput,
                StepRecorder.elapsedMs(llmStart),
                llmResponse.getErrorMessage());

        // Step 6: the guardrail checks the number that will actually be paid,
        // plus the language and any conflicting amount the model produced.
        long guardrailStart = System.nanoTime();
        boolean amountWithinPolicy = guardrailChecker.checkCompensationAmount(authoritativeAmount);
        boolean languageAcceptable =
                modelDisabled || guardrailChecker.checkForbiddenPhrases(llmResponse.getContent());
        boolean modelAmountConflicts =
                guardrailChecker.mentionsConflictingCompensationAmount(
                        llmResponse.getContent(), authoritativeAmount);
        boolean guardrailPassed = amountWithinPolicy && languageAcceptable && !modelAmountConflicts;
        if (!amountWithinPolicy && "DETERMINED".equals(decision.get("decision_status"))) {
            decision.put("decision_status", "POLICY_CONFLICT");
            decision.put("authoritative_amount", null);
            decision.put("amount_is_candidate", true);
            decision.put("approval_required", true);
            degraded = true;
        }

        Map<String, Object> guardrailOutput = new LinkedHashMap<>();
        guardrailOutput.put("passed", guardrailPassed);
        guardrailOutput.put("amount_within_policy", amountWithinPolicy);
        guardrailOutput.put("language_acceptable", languageAcceptable);
        guardrailOutput.put("model_proposed_amount", modelAmountConflicts);
        guardrailOutput.put("model_amount_conflicts_with_policy", modelAmountConflicts);
        guardrailOutput.put("checked_amount", authoritativeAmount);
        steps.record(
                HarnessConstants.STEP_GUARDRAIL,
                "赔付金额与措辞校验",
                guardrailPassed ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                Collections.emptyMap(),
                guardrailOutput,
                StepRecorder.elapsedMs(guardrailStart));

        // Step 7: response shape.
        long formatStart = System.nanoTime();
        Map<String, Object> formatted =
                outputFormatter.formatCompensationSuggestion(
                        order,
                        complaintType,
                        timeline,
                        decision,
                        llmResponse.getContent(),
                        retrievalResult,
                        guardrailPassed);
        formatted.put(
                "model_availability",
                modelDisabled
                        ? "DISABLED"
                        : Boolean.TRUE.equals(llmResponse.getSuccess())
                                ? "AVAILABLE"
                                : "UNAVAILABLE");
        formatted.put(
                "amount_safe", decision.get("authoritative_amount") != null && amountWithinPolicy);
        formatted.put("language_safe", languageAcceptable && !modelAmountConflicts);
        if (modelDisabled) {
            formatted.put(
                    "approval_required",
                    Boolean.TRUE.equals(decision.get("approval_required")) || !timeline.complete());
            formatted.put("approval_reasons", decision.get("decision_reasons"));
            formatted.put("customer_message_status", "DRAFT");
            formatted.put("validation_errors", List.of());
        } else if (!Boolean.TRUE.equals(formatted.get("model_output_parsed"))) {
            degraded = true;
            steps.record(
                    "model_validation",
                    "说明结构核验",
                    HarnessConstants.STATUS_FAILED,
                    Collections.emptyMap(),
                    Map.of("validation_errors", formatted.get("validation_errors")),
                    0);
        }
        formatted.put(HarnessConstants.OUTPUT_DEGRADED, degraded || !guardrailPassed);
        steps.record(
                HarnessConstants.STEP_FORMAT,
                "输出格式化",
                HarnessConstants.STATUS_SUCCESS,
                Collections.emptyMap(),
                Collections.singletonMap("field_count", formatted.size()),
                StepRecorder.elapsedMs(formatStart));

        return formatted;
    }

    private ToolResult invokeTool(
            StepRecorder steps, String toolName, Map<String, Object> params, String label) {
        long start = System.nanoTime();
        boolean required =
                HarnessConstants.TOOL_ORDER_QUERY.equals(toolName)
                        || HarnessConstants.TOOL_COMPENSATION_RULE.equals(toolName);
        ToolResult result;
        try {
            result = toolGateway.invoke(toolName, params);
        } catch (RuntimeException e) {
            steps.record(
                    HarnessConstants.STEP_TOOL_CALL,
                    label,
                    toolName,
                    HarnessConstants.STATUS_FAILED,
                    params,
                    Map.of(
                            "required",
                            required,
                            "retryable",
                            true,
                            "error_type",
                            e.getClass().getSimpleName()),
                    StepRecorder.elapsedMs(start),
                    "Dependency unavailable");
            if (required) {
                throw e;
            }
            return ToolResult.builder()
                    .toolName(toolName)
                    .success(false)
                    .errorMessage("Optional dependency unavailable")
                    .build();
        }
        Map<String, Object> stepOutput = JsonUtil.toMap(result);
        stepOutput.put("required", required);
        steps.record(
                HarnessConstants.STEP_TOOL_CALL,
                label,
                toolName,
                Boolean.TRUE.equals(result.getSuccess())
                        ? HarnessConstants.STATUS_SUCCESS
                        : HarnessConstants.STATUS_FAILED,
                params,
                stepOutput,
                StepRecorder.elapsedMs(start),
                result.getErrorMessage());
        return result;
    }

    static BigDecimal toAmount(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String buildRetrievalQuery(
            String complaintType, Map<String, Object> decision, OrderTimeline timeline) {
        StringBuilder query = new StringBuilder("赔付 ").append(complaintType);
        Object rules = decision.get("matched_rules");
        if (rules instanceof Iterable<?> iterable) {
            for (Object rule : iterable) {
                if (rule instanceof Map<?, ?> map && map.get("rule_name") != null) {
                    query.append(' ').append(map.get("rule_name"));
                }
            }
        }
        if (timeline.overtime()) {
            query.append(" 超时").append(timeline.overtimeMinutes()).append("分钟");
        }
        return query.toString().trim();
    }

    private static String buildJustificationPrompt(
            OrderInfo order,
            OrderTimeline timeline,
            String complaintType,
            Map<String, Object> decision,
            RetrievalService.RetrievalResult retrievalResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 订单基本信息\n")
                .append(
                        JsonUtil.toPrettyJson(
                                OutputFormatter.basicEvidence(order, timeline).get("order")))
                .append("\n\n");
        sb.append("## 时间轴拆解\n").append(JsonUtil.toPrettyJson(timeline.asMap())).append("\n\n");
        sb.append("## 投诉类型\n").append(complaintType).append("\n\n");
        sb.append("## 规则引擎的最终决定（权威，不可修改）\n").append(JsonUtil.toPrettyJson(decision)).append("\n\n");

        sb.append("## 可引用证据（evidence_id 对应对象键）\n")
                .append(
                        JsonUtil.toPrettyJson(
                                OutputFormatter.compensationEvidence(order, timeline, decision)))
                .append("\n\n");
        sb.append("## 历史相似案例\n");
        if (retrievalResult.getItems().isEmpty()) {
            sb.append("（无命中，请在 risk_warnings 中说明缺少可比案例）\n");
        } else {
            for (RetrievalService.RetrievalItem item : retrievalResult.getItems()) {
                sb.append(
                        String.format(
                                "- [%s][%s] %s: %s%n",
                                item.getSourceType(),
                                item.getSourceId(),
                                item.getTitle(),
                                item.getContent()));
            }
        }

        sb.append("\n## 输出 JSON，字段如下（不含金额）\n");
        sb.append("{\n");
        sb.append("  \"reason\": \"向审核人解释这一档位为何适用，引用具体字段\",\n");
        sb.append("  \"customer_message\": \"可直接发给用户的说明，不含承诺性表述\",\n");
        sb.append("  \"risk_warnings\": [\"风险与证据缺口\"],\n");
        sb.append(
                "  \"evidence_chain\":"
                        + " [{\"fact\":\"规则确定金额\",\"evidence_id\":\"decision\","
                        + "\"evidence_version\":\"1\",\"field_path\":\"authoritative_amount\","
                        + "\"observed_value\":0,\"unit\":\"CNY\"}],\n");
        sb.append("  \"applicable_rules\": [\"本次matched_rules中的规则ID\"],\n");
        sb.append("  \"escalate\": true 或 false,\n");
        sb.append("  \"confidence\": \"HIGH | MEDIUM | LOW\"\n");
        sb.append("}\n");
        return sb.toString();
    }
}
