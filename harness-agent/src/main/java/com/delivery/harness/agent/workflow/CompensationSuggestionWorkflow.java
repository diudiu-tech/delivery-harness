package com.delivery.harness.agent.workflow;

import com.delivery.harness.agent.formatter.OutputFormatter;
import com.delivery.harness.agent.guardrail.GuardrailChecker;
import com.delivery.harness.common.config.HarnessConstants;
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

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationSuggestionWorkflow {

    private final WorkflowEngine workflowEngine;
    private final ToolGateway toolGateway;
    private final RetrievalService retrievalService;
    private final LlmGateway llmGateway;
    private final OutputFormatter outputFormatter;
    private final GuardrailChecker guardrailChecker;

    @PostConstruct
    public void init() {
        workflowEngine.registerHandler(HarnessConstants.SCENARIO_COMPENSATION, this::handle);
    }

    private Map<String, Object> handle(Map<String, Object> input, WorkflowExecution execution) {
        String orderId = (String) input.get("order_id");
        String complaintType = (String) input.getOrDefault("complaint_type", "OVERTIME");

        // Step 1: Query order
        ToolResult orderResult = toolGateway.invoke(HarnessConstants.TOOL_ORDER_QUERY, Collections.singletonMap("order_id", (Object) orderId));
        addStep(execution, HarnessConstants.STEP_TOOL_CALL, "查询订单",
                HarnessConstants.STATUS_SUCCESS, Collections.singletonMap("order_id", (Object) orderId), JsonUtil.toMap(orderResult));

        // Step 2: Match compensation rules
        int overtimeMinutes = 10; // would be calculated from order data in production
        ToolResult compResult = toolGateway.invoke(HarnessConstants.TOOL_COMPENSATION_RULE,
                new HashMap<String, Object>() {{ put("complaint_type", complaintType); put("overtime_minutes", overtimeMinutes); }});
        addStep(execution, HarnessConstants.STEP_TOOL_CALL, "匹配赔付规则",
                HarnessConstants.STATUS_SUCCESS, Collections.singletonMap("complaint_type", (Object) complaintType), JsonUtil.toMap(compResult));

        // Step 3: Retrieve similar cases
        RetrievalService.RetrievalResult retrievalResult = retrievalService.retrieve(
                RetrievalService.RetrievalRequest.builder()
                        .query("超时赔付 " + complaintType)
                        .scenario(HarnessConstants.SCENARIO_COMPENSATION)
                        .topK(3)
                        .build());
        addStep(execution, HarnessConstants.STEP_RETRIEVAL, "检索相似案例",
                HarnessConstants.STATUS_SUCCESS, Collections.singletonMap("query", (Object) complaintType),
                Collections.singletonMap("total_found", (Object) retrievalResult.getTotalFound()));

        // Step 4: LLM generate suggestion
        String prompt = buildCompensationPrompt(orderId, complaintType, orderResult, compResult, retrievalResult);
        LlmChatResponse llmResponse = llmGateway.chat(LlmChatRequest.builder()
                .scenario(HarnessConstants.SCENARIO_COMPENSATION)
                .messages(Arrays.asList(
                        LlmMessage.system("你是即时配送行业的赔付建议专家。请基于订单信息、赔付规则和历史案例，给出合理的赔付建议。请用JSON格式输出。"),
                        LlmMessage.user(prompt)
                ))
                .build());
        addStep(execution, HarnessConstants.STEP_LLM_CALL, "LLM生成赔付建议",
                HarnessConstants.STATUS_SUCCESS, Collections.emptyMap(), Collections.singletonMap("content_length",
                        (Object) (llmResponse.getContent() != null ? llmResponse.getContent().length() : 0)));

        // Step 5: Guardrail
        boolean guardrailPassed = guardrailChecker.checkCompensation(llmResponse.getContent());
        addStep(execution, HarnessConstants.STEP_GUARDRAIL, "赔付金额校验",
                guardrailPassed ? HarnessConstants.STATUS_SUCCESS : HarnessConstants.STATUS_FAILED,
                Collections.emptyMap(), Collections.singletonMap("passed", (Object) guardrailPassed));

        // Step 6: Format
        Map<String, Object> formatted = outputFormatter.formatCompensationSuggestion(
                orderId, complaintType, llmResponse.getContent(), compResult, retrievalResult);
        formatted.put("guardrail_passed", guardrailPassed);
        addStep(execution, HarnessConstants.STEP_FORMAT, "输出格式化",
                HarnessConstants.STATUS_SUCCESS, Collections.emptyMap(), formatted);

        return formatted;
    }

    private String buildCompensationPrompt(String orderId, String complaintType,
                                            ToolResult orderResult, ToolResult compResult,
                                            RetrievalService.RetrievalResult retrievalResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 订单信息\n").append(JsonUtil.toPrettyJson(orderResult.getData())).append("\n\n");
        sb.append("## 投诉类型: ").append(complaintType).append("\n\n");
        sb.append("## 规则匹配结果\n").append(JsonUtil.toPrettyJson(compResult.getData())).append("\n\n");
        sb.append("## 历史相似案例\n");
        for (RetrievalService.RetrievalItem item : retrievalResult.getItems()) {
            sb.append(String.format("- %s: %s\n", item.getTitle(), item.getContent()));
        }
        sb.append("\n## 请以JSON格式输出赔付建议\n");
        sb.append("1. should_compensate: 是否应赔付\n");
        sb.append("2. reason: 赔付/不赔付理由\n");
        sb.append("3. suggested_amount: 建议金额\n");
        sb.append("4. suggested_method: 赔付方式\n");
        sb.append("5. risk_warnings: 风险提示\n");
        sb.append("6. approval_required: 是否需要人工审批\n");
        return sb.toString();
    }

    private void addStep(WorkflowExecution execution, String stepType, String name,
                         String status, Map<String, Object> input, Map<String, Object> output) {
        execution.getSteps().add(WorkflowExecution.StepExecution.builder()
                .order(execution.getSteps().size() + 1)
                .stepType(stepType).name(name).status(status)
                .input(input).output(output).durationMs(0L).build());
    }
}
