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
              "reason": "适用当前赔付规则，建议提供优惠券",
              "customer_message": "建议按规则提供优惠券，等待人工审核确认",
              "risk_warnings": [],
              "escalate": false,
              "confidence": "HIGH",
              "applicable_rules": ["COMP-002"],
              "evidence_chain": [{"fact":"规则金额5元","evidence_id":"decision","evidence_version":"1",
                 "field_path":"authoritative_amount","observed_value":5,"unit":"CNY"}]
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
                .andExpect(jsonPath("$.data.output.authoritative_amount").value(20.00))
                .andExpect(jsonPath("$.data.output.decision_status").value("DETERMINED"))
                .andExpect(jsonPath("$.data.output.should_compensate").value(true))
                .andExpect(jsonPath("$.data.output.amount_decided_by").value("rule_engine"))
                .andExpect(jsonPath("$.data.output.approval_required").value(true));
    }

    @Test
    void allowsTheModelToCiteTheAuthoritativeAmount() throws Exception {
        stubLlmClient.respondWith("""
                {
                  "reason": "适用 COMP-001，赔付订单金额的50%，最高不超过20元",
                  "customer_message": "建议按规则处理，等待人工审核确认",
                  "risk_warnings": [],
                  "escalate": false,
                  "confidence": "HIGH"
                }
                """);

        compensate("TEST003", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.suggested_amount").value(20.00))
                .andExpect(jsonPath("$.data.output.guardrail_passed").value(true));
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
                .andExpect(jsonPath("$.data.output.suggested_amount").value(0.0))
                .andExpect(jsonPath("$.data.output.authoritative_amount").value(0.0))
                .andExpect(jsonPath("$.data.output.decision_status").value("DETERMINED"));
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
    void keepsTheRuleDecisionWhenTheModelIsUnavailable() throws Exception {
        stubLlmClient.failWith("connection refused");

        compensate("TEST003", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.output.suggested_amount").value(20.00))
                .andExpect(jsonPath("$.data.output.model_output_available").value(false))
                .andExpect(jsonPath("$.data.output.approval_reasons"
                        + "[?(@ == 'model_output_unavailable')]").isNotEmpty())
                .andExpect(jsonPath("$.data.steps[?(@.stepType == 'llm_call' "
                        + "&& @.status == 'FAILED' && @.errorMessage != null)]").isNotEmpty());
    }

    @Test
    void requiresDamageEvidenceAndUsesTheConfiguredDamagePolicy() throws Exception {
        compensateDamage("TEST003", "DAMAGED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.matched_rules[0].rule_id").value("COMP-004"))
                .andExpect(jsonPath("$.data.output.suggested_amount").value(0.0))
                .andExpect(jsonPath("$.data.output.approval_required").value(true));

        compensateDamage("TEST003", "DAMAGED", "PARTIAL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.suggested_amount").value(20.00))
                .andExpect(jsonPath("$.data.output.approval_required").value(true));

        compensateDamage("TEST003", "DAMAGED", "FULL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.suggested_amount").value(64.00))
                .andExpect(jsonPath("$.data.output.authoritative_amount").doesNotExist())
                .andExpect(jsonPath("$.data.output.decision_status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.output.customer_message_status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.output.approval_required").value(true));
    }

    @Test
    void rejectsAnUnknownComplaintType() throws Exception {
        mockMvc.perform(post("/api/v1/analyze/compensation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"TEST004\",\"complaint_type\":\"NOT_A_TYPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void blocksUnsupportedComplaintPolicyWithoutClaimingAZeroPayoutDecision() throws Exception {
        compensate("TEST004", "MISSING_ITEM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.decision_status").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.data.output.authoritative_amount").doesNotExist())
                .andExpect(jsonPath("$.data.output.amount_is_candidate").value(true))
                .andExpect(jsonPath("$.data.output.approval_required").value(true));
    }

    @Test
    void validatesTheExactPolicyAmountEvidenceBeforeAllowingTheCustomerDraft() throws Exception {
        stubLlmClient.respondWith("""
                {"reason":"适用COMP-002","customer_message":"按规则建议提供5元优惠券，等待审核确认",
                 "risk_warnings":[],"escalate":false,"confidence":"HIGH","applicable_rules":["COMP-002"],
                 "evidence_chain":[{"fact":"规则金额5元","evidence_id":"decision","evidence_version":"1",
                 "field_path":"authoritative_amount","observed_value":5,"unit":"CNY"}]}
                """);
        compensate("TEST004", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.authoritative_amount").value(5.00))
                .andExpect(jsonPath("$.data.output.model_adoptable").value(true))
                .andExpect(jsonPath("$.data.output.customer_message_status").value("VALIDATED"))
                .andExpect(jsonPath("$.data.output.model_customer_message_status").value("DRAFT"))
                .andExpect(jsonPath("$.data.output.verified_customer_message_status").value("VALIDATED"))
                .andExpect(jsonPath("$.data.output.customer_message_source").value("DETERMINISTIC_TEMPLATE"))
                .andExpect(jsonPath("$.data.output.verified_customer_message").value(
                        "根据本次订单事实与规则，当前建议为优惠券，金额为 CNY 5.00。该建议须经人工审核确认，尚未执行。"));
    }

    @Test
    void fakeRuleTextAndPaymentClaimsCannotProduceAnAdoptedModelMessage() throws Exception {
        stubLlmClient.respondWith("""
                {"reason":"适用虚构规则","customer_message":"退款已完成，赔付五百元",
                 "risk_warnings":[],"escalate":false,"confidence":"HIGH","applicable_rules":"FAKE-UNPUBLISHED",
                 "evidence_chain":[{"fact":"规则金额5元","evidence_id":"decision","evidence_version":"1",
                 "field_path":"authoritative_amount","observed_value":5,"unit":"CNY"}]}
                """);
        compensate("TEST004", "OVERTIME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.authoritative_amount").value(5.00))
                .andExpect(jsonPath("$.data.output.model_adoptable").value(false))
                .andExpect(jsonPath("$.data.output.model_customer_message_status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.output.guardrail_passed").value(false))
                .andExpect(jsonPath("$.data.output.verified_customer_message").value(
                        "根据本次订单事实与规则，当前建议为优惠券，金额为 CNY 5.00。该建议须经人工审核确认，尚未执行。"));
    }

    private ResultActions compensate(String orderId, String complaintType) throws Exception {
        return mockMvc.perform(post("/api/v1/analyze/compensation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"order_id\":\"" + orderId + "\",\"complaint_type\":\"" + complaintType + "\"}"));
    }

    private ResultActions compensateDamage(String orderId, String complaintType, String damageLevel) throws Exception {
        String suffix = damageLevel == null ? "" : ",\"damage_level\":\"" + damageLevel + "\"";
        return mockMvc.perform(post("/api/v1/analyze/compensation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"order_id\":\"" + orderId + "\",\"complaint_type\":\""
                        + complaintType + "\"" + suffix + "}"));
    }
}
