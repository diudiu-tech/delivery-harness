package com.delivery.harness.agent.workflow;

import com.delivery.harness.agent.formatter.OutputFormatter;
import com.delivery.harness.agent.guardrail.GuardrailChecker;
import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.llm.gateway.*;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AbnormalOrderAnalysisWorkflow {

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
        String orderId = (String) input.get("order_id");

        // Step 1: Query order details
        ToolResult orderResult = executeToolStep(execution, HarnessConstants.TOOL_ORDER_QUERY,
                Collections.<String, Object>singletonMap("order_id", orderId), "查询订单详情");

        // Step 2: Query ETA
        ToolResult etaResult = executeToolStep(execution, HarnessConstants.TOOL_ETA_QUERY,
                new HashMap<String, Object>() {{
                    put("origin_lat", 39.9087); put("origin_lng", 116.3975);
                    put("dest_lat", 39.9150); put("dest_lng", 116.4050);
                }}, "查询ETA");

        // Step 3: Query capacity
        ToolResult capacityResult = executeToolStep(execution, HarnessConstants.TOOL_CAPACITY_QUERY,
                Collections.singletonMap("station_id", (Object) "S001"), "查询运力");

        // Step 4: Retrieve relevant rules and cases
        RetrievalService.RetrievalResult retrievalResult = executeRetrievalStep(execution,
                "超时配送异常", HarnessConstants.SCENARIO_ABNORMAL_ORDER);

        // Step 5: LLM analysis
        String prompt = buildAnalysisPrompt(orderId, orderResult, etaResult, capacityResult, retrievalResult);
        LlmChatResponse llmResponse = executeLlmStep(execution, prompt);

        // Step 6: Guardrail check
        boolean guardrailPassed = guardrailChecker.check(
                llmResponse.getContent(), HarnessConstants.SCENARIO_ABNORMAL_ORDER);
        addStep(execution, HarnessConstants.STEP_GUARDRAIL, "合规性校验",
                guardrailPassed ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                Collections.emptyMap(), Collections.singletonMap("passed", (Object) guardrailPassed));

        // Step 7: Format output
        Map<String, Object> formatted = outputFormatter.formatAbnormalOrderAnalysis(
                orderId, llmResponse.getContent(), retrievalResult);
        formatted.put("guardrail_passed", guardrailPassed);
        addStep(execution, HarnessConstants.STEP_FORMAT, "输出格式化",
                HarnessConstants.STATUS_SUCCESS, Collections.emptyMap(), formatted);

        return formatted;
    }

    private ToolResult executeToolStep(WorkflowExecution execution, String toolName,
                                       Map<String, Object> params, String stepName) {
        long start = System.currentTimeMillis();
        ToolResult result = toolGateway.invoke(toolName, params);
        addStep(execution, HarnessConstants.STEP_TOOL_CALL, stepName,
                result.getSuccess() ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                params, JsonUtil.toMap(result));
        return result;
    }

    private RetrievalService.RetrievalResult executeRetrievalStep(WorkflowExecution execution,
                                                                    String query, String scenario) {
        long start = System.currentTimeMillis();
        RetrievalService.RetrievalResult result = retrievalService.retrieve(
                RetrievalService.RetrievalRequest.builder()
                        .query(query).scenario(scenario).topK(5).build());
        addStep(execution, HarnessConstants.STEP_RETRIEVAL, "知识检索",
                HarnessConstants.STATUS_SUCCESS, Collections.singletonMap("query", (Object) query),
                Collections.singletonMap("total_found", (Object) result.getTotalFound()));
        return result;
    }

    private LlmChatResponse executeLlmStep(WorkflowExecution execution, String prompt) {
        LlmChatResponse response = llmGateway.chat(LlmChatRequest.builder()
                .scenario(HarnessConstants.SCENARIO_ABNORMAL_ORDER)
                .messages(Arrays.asList(
                        LlmMessage.system("你是即时配送行业的异常单分析专家。请基于提供的订单数据、ETA数据、运力数据和相关规则，分析异常原因并给出处置建议。请用JSON格式输出。"),
                        LlmMessage.user(prompt)
                ))
                .build());
        addStep(execution, HarnessConstants.STEP_LLM_CALL, "LLM归因分析",
                response.getSuccess() ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                Collections.singletonMap("prompt_length", (Object) prompt.length()),
                Collections.singletonMap("response_length", (Object) (response.getContent() != null ? response.getContent().length() : 0)));
        return response;
    }

    private String buildAnalysisPrompt(String orderId, ToolResult orderResult,
                                        ToolResult etaResult, ToolResult capacityResult,
                                        RetrievalService.RetrievalResult retrievalResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 订单信息\n").append(JsonUtil.toPrettyJson(orderResult.getData())).append("\n\n");
        sb.append("## ETA信息\n").append(JsonUtil.toPrettyJson(etaResult.getData())).append("\n\n");
        sb.append("## 运力信息\n").append(JsonUtil.toPrettyJson(capacityResult.getData())).append("\n\n");
        sb.append("## 相关规则与案例\n");
        for (RetrievalService.RetrievalItem item : retrievalResult.getItems()) {
            sb.append(String.format("- [%s] %s: %s\n", item.getSourceType(), item.getTitle(), item.getContent()));
        }
        sb.append("\n## 请分析以下内容并以JSON格式输出\n");
        sb.append("1. primary_cause: 主要原因\n");
        sb.append("2. secondary_causes: 次要原因列表\n");
        sb.append("3. evidence_chain: 证据链（每项包含fact, source, timestamp）\n");
        sb.append("4. applicable_rules: 适用规则\n");
        sb.append("5. suggested_actions: 建议动作\n");
        sb.append("6. risk_notes: 风险提示\n");
        sb.append("7. confidence: HIGH/MEDIUM/LOW\n");
        return sb.toString();
    }

    private void addStep(WorkflowExecution execution, String stepType, String name,
                         String status, Map<String, Object> input, Map<String, Object> output) {
        execution.getSteps().add(WorkflowExecution.StepExecution.builder()
                .order(execution.getSteps().size() + 1)
                .stepType(stepType)
                .name(name)
                .status(status)
                .input(input)
                .output(output)
                .durationMs(0L)
                .build());
    }
}
