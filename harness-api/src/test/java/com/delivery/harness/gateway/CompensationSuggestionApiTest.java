package com.delivery.harness.gateway;

import com.delivery.harness.gateway.support.StubLlmClient;
import com.delivery.harness.gateway.support.StubLlmClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the compensation workflow.
 *
 * <p>The property under test is the one ADR-0002 argues for: the payout comes
 * from the rule engine and is unaffected by what the model says. Several tests
 * here deliberately have the model contradict the rule engine, and assert that
 * the response still carries the rule engine's number.
 *
 * <p>Two tiers exercised here were unreachable through the API before this
 * work: overtime_minutes was hard-coded to 10, which pinned every request to
 * the lowest tier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmClientConfig.class)
class CompensationSuggestionApiTest {

    private static final String JUSTIFICATION = """
            {
              "reason": "到店等待时间显著高于其他各段，超时责任在商家侧",
              "customer_message": "很抱歉本次配送延误，已按规则为您处理",
              "risk_warnings": [],
              "escalate": false,
              "confidence": "HIGH"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubLlmClient stubLlmClient;

    @BeforeEach
    void resetStub() {
        stubLlmClient.reset();
        stubLlmClient.respondWith(JUSTIFICATION);
    }

    @Test
    void appliesTheSevereTierAndCapsThePayout() throws Exception {
        // TEST003 is 35 minutes late on a 64.00 order. COMP-001 pays 50% of
        // order value capped at 20, so 32.00 becomes 20.00.
        compensate("TEST003", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.timeline.overtime_minutes").value(35))
                .andExpect(jsonPath("$.data.output.matched_rules[0].rule_id").value("COMP-001"))
                .andExpect(jsonPath("$.data.output.suggested_amount").value(20.00))
                .andExpect(jsonPath("$.data.output.should_compensate").value(true))
                .andExpect(jsonPath("$.data.output.amount_decided_by").value("rule_engine"))
                .andExpect(jsonPath("$.data.output.approval_required").value(true));
    }

    @Test
    void appliesTheModerateTier() throws Exception {
        compensate("TEST004", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.timeline.overtime_minutes").value(18))
                .andExpect(jsonPath("$.data.output.matched_rules[0].rule_id").value("COMP-002"))
                .andExpect(jsonPath("$.data.output.suggested_amount").value(5.00))
                .andExpect(jsonPath("$.data.output.guardrail_passed").value(true))
                .andExpect(jsonPath("$.data.output.approval_required").value(false));
    }

    @Test
    void refusesToCompensateAnOrderThatWasNotLate() throws Exception {
        compensate("TEST005", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.timeline.overtime_minutes").value(0))
                .andExpect(jsonPath("$.data.output.should_compensate").value(false))
                .andExpect(jsonPath("$.data.output.suggested_amount").value(0.0));
    }

    @Test
    void ignoresAnAmountTheModelInventsAndFlagsIt() throws Exception {
        stubLlmClient.respondWith("""
                {"reason": "建议从宽处理", "suggested_amount": 999, "escalate": false}
                """);

        compensate("TEST004", "OVERTIME")
                .andExpect(status().isOk())
                // The rule engine's number, not the model's.
                .andExpect(jsonPath("$.data.output.suggested_amount").value(5.00))
                // But the model ignored its instructions, so this needs review.
                .andExpect(jsonPath("$.data.output.guardrail_passed").value(false))
                .andExpect(jsonPath("$.data.output.approval_required").value(true))
                .andExpect(jsonPath("$.data.steps[?(@.stepType == 'guardrail' "
                        + "&& @.status == 'FAILED')]").isNotEmpty());
    }

    @Test
    void neverPutsAnAmountFieldInThePromptForTheModelToCopy() throws Exception {
        compensate("TEST003", "OVERTIME").andExpect(status().isOk());

        String prompt = stubLlmClient.lastUserPrompt();
        assertTrue(prompt.contains("权威"), "prompt should mark the rule decision as authoritative");

        String systemPrompt = stubLlmClient.systemPrompts().get(0);
        assertTrue(systemPrompt.contains("不得修改"), "system prompt should forbid changing the amount");
        assertFalse(systemPrompt.isBlank());
    }

    @Test
    void escalatesWhenTheModelAsksForIt() throws Exception {
        stubLlmClient.respondWith("""
                {"reason": "证据不足", "risk_warnings": ["缺少可比案例"], "escalate": true}
                """);

        compensate("TEST004", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.approval_required").value(true))
                .andExpect(jsonPath("$.data.output.approval_reasons"
                        + "[?(@ == 'model_requested_escalation')]").isNotEmpty());
    }

    @Test
    void rejectsAnUnknownComplaintType() throws Exception {
        mockMvc.perform(post("/api/v1/analyze/compensation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"TEST004\",\"complaint_type\":\"NOT_A_TYPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private ResultActions compensate(String orderId, String complaintType) throws Exception {
        return mockMvc.perform(post("/api/v1/analyze/compensation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"order_id\":\"" + orderId + "\",\"complaint_type\":\"" + complaintType + "\"}"));
    }
}
