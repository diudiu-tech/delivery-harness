package com.delivery.harness.agent.workflow;

import com.delivery.harness.agent.formatter.OutputFormatter;
import com.delivery.harness.agent.guardrail.GuardrailChecker;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.delivery.harness.platform.quality.RuntimeBinding;
import com.delivery.harness.tool.compensation.CompensationRuleToolkit;
import com.delivery.harness.tool.gateway.ToolGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrustedWorkflowTest {
    private WorkflowEngine engine;
    private ToolGateway tools;
    private StubRetrieval retrieval;
    private StubModel model;
    private RuleInfo rule;
    private RuleBaseService rules;
    @BeforeEach
    void setup() {
        engine = new WorkflowEngine(); tools = new ToolGateway(100);
        retrieval = new StubRetrieval(); model = new StubModel();
        tools.register("order_query", new ToolDefinition(), p -> ToolResult.builder().success(true).data(order()).build());
        tools.register("eta_query", new ToolDefinition(), p -> ToolResult.builder().success(true).data(Map.of("estimated_minutes", 20)).build());
        tools.register("capacity_query", new ToolDefinition(), p -> ToolResult.builder().success(true).data(Map.of("capacity_ratio", 0.5)).build());
        rules = new RuleBaseService();
        rule = RuleInfo.builder().ruleId("COMP-TEST").ruleName("Test policy").ruleType("compensation").priority(10)
                .conditions(Map.of("complaint_type", "OVERTIME", "min_overtime_minutes", 1))
                .actions(Map.of("amount_type", "FIXED", "amount", 5, "method", "券", "confidence", "HIGH")).build();
        rules.save(rule); new CompensationRuleToolkit(tools, rules).init();
        OutputFormatter formatter = new OutputFormatter(new StructuredOutputParser());
        GuardrailChecker checker = new GuardrailChecker();
        new AbnormalOrderAnalysisWorkflow(engine, tools, retrieval, model, formatter, checker).init();
        new CompensationSuggestionWorkflow(engine, tools, retrieval, model, formatter, checker).init();
    }
    @Test
    void bothWorkflowsKeepDeterministicResultsAndFailedModelStepsWhenModelIsDown() {
        for (String scenario : List.of("abnormal_order_analysis", "compensation_suggestion")) {
            WorkflowExecution execution = engine.execute(scenario, Map.of("order_id", "ORDER-1", "complaint_type", "OVERTIME"));
            assertEquals("DEGRADED", execution.getStatus(), scenario);
            assertNotNull(execution.getOutput().get("timeline_v2"));
            assertTrue(execution.getSteps().stream().anyMatch(step -> "llm_call".equals(step.getStepType()) && "FAILED".equals(step.getStatus()) && step.getErrorMessage() != null));
            if (scenario.equals("compensation_suggestion")) { assertEquals(5.0, ((Number) execution.getOutput().get("authoritative_amount")).doubleValue()); }
        }
    }
    @Test
    void optionalToolAndRetrievalExceptionsAreRecordedAndDegradeInsteadOfDiscardingTimeline() {
        tools.register("eta_query", new ToolDefinition(), p -> { throw new IllegalStateException("timeout"); });
        retrieval.unavailable = true;
        WorkflowExecution execution = engine.execute("abnormal_order_analysis", Map.of("order_id", "ORDER-1"));
        assertEquals("DEGRADED", execution.getStatus());
        assertNotNull(execution.getOutput().get("timeline_v2"));
        assertTrue(execution.getSteps().stream().anyMatch(s -> "eta_query".equals(s.getToolName()) && "FAILED".equals(s.getStatus())));
        assertTrue(execution.getSteps().stream().anyMatch(s -> "retrieval".equals(s.getStepType()) && "FAILED".equals(s.getStatus())));
    }
    @Test
    void invalidRequiredOrderContractStopsWorkflowWithRecordedFailure() {
        tools.register("order_query", new ToolDefinition(), p -> ToolResult.builder().success(true).data(Map.of("orderId", "WRONG-ORDER")).build());
        assertThrows(com.delivery.harness.common.exception.ToolException.class,
                () -> engine.execute("abnormal_order_analysis", Map.of("order_id", "ORDER-1")));
        WorkflowExecution failed = engine.takeFailedExecution();
        assertEquals("FAILED", failed.getStatus());
        assertEquals(2, failed.getSteps().size());
        assertEquals("data_validation", failed.getSteps().get(1).getStepType());
        assertEquals(0, model.calls);
        assertEquals(0, retrieval.calls);
    }

    @Test
    void deterministicRuntimeNeverCallsModelAndUsesFrozenServerOrder() {
        tools.register("order_query", new ToolDefinition(), p -> { throw new AssertionError("Frozen order must be used"); });
        try (RuntimeBinding ignored = RuntimeBinding.bind(order(), List.of(rule), Map.of("deterministic_only", true), false)) {
            WorkflowExecution execution = engine.execute("compensation_suggestion", Map.of("order_id", "ORDER-1", "complaint_type", "OVERTIME"));
            assertEquals("SUCCESS", execution.getStatus());
            assertEquals("DISABLED", execution.getOutput().get("model_availability"));
            assertEquals(0, model.calls);
        }
    }
    @Test
    void liveOrdersNeverUseSyntheticReferenceTools() {
        OrderInfo live = order();
        live.setExtraInfo(Map.of("source_type", "LIVE_READ_ONLY"));
        tools.register("eta_query", new ToolDefinition(), p -> { throw new AssertionError("Live order cannot use synthetic ETA"); });
        tools.register("capacity_query", new ToolDefinition(), p -> { throw new AssertionError("Live order cannot use synthetic capacity"); });
        try (RuntimeBinding ignored = RuntimeBinding.bind(live, List.of(rule), Map.of("deterministic_only", true), false)) {
            WorkflowExecution execution = engine.execute("abnormal_order_analysis", Map.of("order_id", "ORDER-1"));
            assertEquals("DEGRADED", execution.getStatus());
            assertEquals(2, execution.getSteps().stream().filter(s -> "HISTORY_UNAVAILABLE".equals(s.getOutput().get("error_code"))).count());
            assertEquals(0, model.calls);
        }
    }

    @Test
    void clientSelfReportedVerificationCannotAuthorizeDamageDecision() {
        RuleInfo damage = RuleInfo.builder().ruleId("COMP-DAMAGE").ruleType("compensation").priority(10)
                .conditions(Map.of("complaint_type", "DAMAGED"))
                .actions(Map.of("amount_type", "DAMAGE_LEVEL", "partial_rate", 0.5, "partial_cap", 20)).build();
        rules.save(damage);
        WorkflowExecution execution = engine.execute("compensation_suggestion",
                Map.of("order_id", "ORDER-1", "complaint_type", "DAMAGED", "damage_level", "FULL", "damage_evidence_verified", true));
        assertEquals("INSUFFICIENT_EVIDENCE", execution.getOutput().get("decision_status"));
        assertNull(execution.getOutput().get("authoritative_amount"));
        assertEquals(30.0, ((Number) execution.getOutput().get("suggested_amount")).doubleValue());
    }

    private static final class StubRetrieval extends RetrievalService {
        private boolean unavailable;
        private int calls;
        private StubRetrieval() { super(null, null, null); }
        @Override public RetrievalResult retrieve(RetrievalRequest request) {
            calls++;
            if (unavailable) { throw new IllegalStateException("index down"); }
            return RetrievalResult.builder().items(List.of()).totalFound(0).build();
        }
    }
    private static final class StubModel extends LlmGateway {
        private int calls;
        private StubModel() { super(null, null); }
        @Override public com.delivery.harness.llm.gateway.LlmChatResponse chat(com.delivery.harness.llm.gateway.LlmChatRequest request) {
            calls++;
            throw new LlmException("model", "unavailable");
        }
    }
    private static OrderInfo order() {
        LocalDateTime created = LocalDateTime.of(2026, 9, 1, 10, 0);
        return OrderInfo.builder().orderId("ORDER-1").status("DELIVERED").stationId("ST-1").orderAmount(new BigDecimal("30"))
                .createTime(created).acceptTime(created.plusMinutes(3)).arriveShopTime(created.plusMinutes(10))
                .pickupTime(created.plusMinutes(38)).deliveryTime(created.plusMinutes(58))
                .expectedDeliveryTime(created.plusMinutes(40)).build();
    }
}
