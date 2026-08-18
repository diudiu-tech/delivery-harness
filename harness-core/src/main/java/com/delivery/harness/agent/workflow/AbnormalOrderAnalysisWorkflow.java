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
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fixed workflow: attribute an abnormal order to a cause.
 *
 * <p>Every tool argument is derived from the order under analysis. An earlier
 * revision hard-coded the ETA coordinates, the station ID and the retrieval
 * query, so the workflow assembled identical evidence regardless of which
 * order it was asked about.
 *
 * <p>The model's job here is genuine. The deterministic timeline says which
 * leg was longest but not why: a long road leg in a rainstorm and a long road
 * leg on a clear day are the same number, and only the surrounding evidence
 * separates them. {@link OrderTimeline#dominantLegLabel()} is carried through
 * to the response as the baseline the model has to beat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbnormalOrderAnalysisWorkflow {

    private static final String SYSTEM_PROMPT =
            "你是即时配送行业的异常单归因专家。\n"
            + "你会收到一份订单的时间轴拆解、路径耗时基准（ETA）、站点运力快照和适用规则。\n"
            + "请判断异常主因，并为每条结论指出它依据的具体数据字段。\n"
            + "规则：\n"
            + "1. 只使用提供的数据，不要编造未给出的事实；数据不足时在 risk_notes 中说明。\n"
            + "2. evidence_chain 的每一项必须引用输入中真实存在的字段名与数值。\n"
            + "3. 若订单未超时，primary_cause 必须为「无异常」。\n"
            + "4. 仅输出一个 JSON 对象，不要输出其他文字。";

    private final WorkflowEngine workflowEngine;
    private final ToolGateway toolGateway;
    private final RetrievalService retrievalService;
    private final LlmGateway llmGateway;
    private final OutputFormatter outputFormatter;
    private final GuardrailChecker guardrailChecker;

    @PostConstruct
    public void init() {
        workflowEngine.registerHandler(HarnessConstants.SCENARIO_ABNORMAL_ORDER, this::handle);
    }

    private Map<String, Object> handle(Map<String, Object> input, WorkflowExecution execution) {
        String orderId = String.valueOf(input.get("order_id"));
        StepRecorder steps = new StepRecorder(execution);

        // Step 1: the order itself. Everything downstream derives from it, so a
        // failure here must stop the workflow rather than be papered over.
        ToolResult orderResult = invokeTool(steps, HarnessConstants.TOOL_ORDER_QUERY,
                Collections.singletonMap("order_id", orderId), "查询订单详情");
        if (!Boolean.TRUE.equals(orderResult.getSuccess())) {
            throw new ToolException(HarnessConstants.TOOL_ORDER_QUERY,
                    orderResult.getErrorMessage() == null ? "order lookup failed" : orderResult.getErrorMessage());
        }
        OrderInfo order = JsonUtil.fromMap(JsonUtil.toMap(orderResult.getData()), OrderInfo.class);

        // Step 2: deterministic timeline, recorded as its own step so the
        // baseline attribution sits next to the model's answer.
        long timelineStart = System.nanoTime();
        OrderTimeline timeline = OrderTimeline.of(order);
        steps.record(HarnessConstants.STEP_ANALYSIS, "时间轴拆解", HarnessConstants.STATUS_SUCCESS,
                Collections.singletonMap("order_id", orderId), timeline.asMap(),
                StepRecorder.elapsedMs(timelineStart));

        // Step 3: what the road leg should have taken, on this order's route.
        Map<String, Object> etaParams = new LinkedHashMap<>();
        etaParams.put("origin_lat", order.getPickupLat());
        etaParams.put("origin_lng", order.getPickupLng());
        etaParams.put("dest_lat", order.getDeliveryLat());
        etaParams.put("dest_lng", order.getDeliveryLng());
        etaParams.put("weather", weatherOf(order));
        ToolResult etaResult = invokeTool(steps, HarnessConstants.TOOL_ETA_QUERY, etaParams, "查询路径基准ETA");

        // Step 4: the station this order actually belonged to.
        ToolResult capacityResult = invokeTool(steps, HarnessConstants.TOOL_CAPACITY_QUERY,
                Collections.singletonMap("station_id", order.getStationId()), "查询站点运力");

        // Step 5: retrieval keyed on this order's dominant leg, not a constant.
        String query = buildRetrievalQuery(order, timeline);
        long retrievalStart = System.nanoTime();
        RetrievalService.RetrievalResult retrievalResult = retrievalService.retrieve(
                RetrievalService.RetrievalRequest.builder()
                        .query(query)
                        .scenario(HarnessConstants.SCENARIO_ABNORMAL_ORDER)
                        .topK(5)
                        .build());
        Map<String, Object> retrievalOutput = new LinkedHashMap<>();
        retrievalOutput.put("total_found", retrievalResult.getTotalFound());
        retrievalOutput.put("source_ids", retrievalResult.getItems().stream()
                .map(RetrievalService.RetrievalItem::getSourceId)
                .collect(Collectors.toList()));
        steps.record(HarnessConstants.STEP_RETRIEVAL, "知识检索", HarnessConstants.STATUS_SUCCESS,
                Collections.singletonMap("query", query), retrievalOutput,
                StepRecorder.elapsedMs(retrievalStart));

        // Step 6: the model.
        String prompt = buildAnalysisPrompt(order, timeline, etaResult, capacityResult, retrievalResult);
        long llmStart = System.nanoTime();
        LlmChatResponse llmResponse = llmGateway.chat(LlmChatRequest.builder()
                .scenario(HarnessConstants.SCENARIO_ABNORMAL_ORDER)
                .messages(Arrays.asList(LlmMessage.system(SYSTEM_PROMPT), LlmMessage.user(prompt)))
                .build());
        Map<String, Object> llmOutput = new LinkedHashMap<>();
        llmOutput.put("model", llmResponse.getModel());
        llmOutput.put("prompt_chars", prompt.length());
        llmOutput.put("response_chars", llmResponse.getContent() == null ? 0 : llmResponse.getContent().length());
        steps.record(HarnessConstants.STEP_LLM_CALL, "LLM归因分析",
                Boolean.TRUE.equals(llmResponse.getSuccess())
                        ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                Collections.singletonMap("scenario", HarnessConstants.SCENARIO_ABNORMAL_ORDER), llmOutput,
                StepRecorder.elapsedMs(llmStart));

        // Step 7: advisory guardrail. A failure is reported rather than
        // swallowed, and does not block the response: a human still reviews it.
        long guardrailStart = System.nanoTime();
        boolean guardrailPassed = guardrailChecker.check(
                llmResponse.getContent(), HarnessConstants.SCENARIO_ABNORMAL_ORDER);
        steps.record(HarnessConstants.STEP_GUARDRAIL, "合规性校验",
                guardrailPassed ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                Collections.emptyMap(), Collections.singletonMap("passed", guardrailPassed),
                StepRecorder.elapsedMs(guardrailStart));

        // Step 8: response shape.
        long formatStart = System.nanoTime();
        Map<String, Object> formatted = outputFormatter.formatAbnormalOrderAnalysis(
                order, timeline, llmResponse.getContent(), retrievalResult, guardrailPassed);
        steps.record(HarnessConstants.STEP_FORMAT, "输出格式化", HarnessConstants.STATUS_SUCCESS,
                Collections.emptyMap(), Collections.singletonMap("field_count", formatted.size()),
                StepRecorder.elapsedMs(formatStart));

        return formatted;
    }

    private ToolResult invokeTool(StepRecorder steps, String toolName, Map<String, Object> params, String label) {
        long start = System.nanoTime();
        ToolResult result = toolGateway.invoke(toolName, params);
        steps.record(HarnessConstants.STEP_TOOL_CALL, label, toolName,
                Boolean.TRUE.equals(result.getSuccess())
                        ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                params, JsonUtil.toMap(result), StepRecorder.elapsedMs(start), result.getErrorMessage());
        return result;
    }

    private static String weatherOf(OrderInfo order) {
        if (order.getExtraInfo() == null) {
            return null;
        }
        Object weather = order.getExtraInfo().get("weather");
        return weather == null ? null : weather.toString();
    }

    /**
     * Retrieval query built from this order's own characteristics. Previously
     * the literal string "超时配送异常" for every request, which meant every
     * order retrieved the same rules regardless of what went wrong.
     */
    private static String buildRetrievalQuery(OrderInfo order, OrderTimeline timeline) {
        StringBuilder query = new StringBuilder();
        query.append(timeline.overtime() ? "超时归因 " : "正常单核查 ");
        query.append(timeline.dominantLegLabel());
        String weather = weatherOf(order);
        if (weather != null && !weather.isBlank() && !"晴".equals(weather)) {
            query.append(' ').append(weather);
        }
        return query.toString().trim();
    }

    private static String buildAnalysisPrompt(
            OrderInfo order,
            OrderTimeline timeline,
            ToolResult etaResult,
            ToolResult capacityResult,
            RetrievalService.RetrievalResult retrievalResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 订单基本信息\n").append(JsonUtil.toPrettyJson(order)).append("\n\n");
        sb.append("## 时间轴拆解（由订单时间戳确定性计算）\n")
                .append(JsonUtil.toPrettyJson(timeline.asMap())).append("\n\n");
        sb.append("## 路径基准 ETA（应耗时，用于与 on_road_minutes 对比）\n")
                .append(JsonUtil.toPrettyJson(etaResult.getData())).append("\n\n");
        sb.append("## 站点运力快照\n")
                .append(JsonUtil.toPrettyJson(capacityResult.getData())).append("\n\n");

        sb.append("## 适用规则与历史案例\n");
        if (retrievalResult.getItems().isEmpty()) {
            sb.append("（无命中，请在 risk_notes 中说明缺少规则依据）\n");
        } else {
            for (RetrievalService.RetrievalItem item : retrievalResult.getItems()) {
                sb.append(String.format("- [%s][%s] %s: %s%n",
                        item.getSourceType(), item.getSourceId(), item.getTitle(), item.getContent()));
            }
        }

        sb.append("\n## 输出 JSON，字段如下\n");
        sb.append("{\n");
        sb.append("  \"primary_cause\": \"主要原因，未超时则填 无异常\",\n");
        sb.append("  \"secondary_causes\": [\"次要原因\"],\n");
        sb.append("  \"evidence_chain\": [{\"fact\": \"结论\", \"source\": \"引用的字段名\", \"value\": \"该字段的数值\"}],\n");
        sb.append("  \"applicable_rules\": [\"命中的 rule_id\"],\n");
        sb.append("  \"suggested_actions\": [\"建议动作\"],\n");
        sb.append("  \"risk_notes\": [\"风险与数据缺口\"],\n");
        sb.append("  \"confidence\": \"HIGH | MEDIUM | LOW\"\n");
        sb.append("}\n");
        return sb.toString();
    }
}
