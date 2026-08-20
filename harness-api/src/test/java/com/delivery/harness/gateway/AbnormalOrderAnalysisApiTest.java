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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the abnormal-order workflow.
 *
 * <p>Before this existed, none of the 30 tests asserted anything about either
 * workflow's output. The suite covered text splitting, JSON extraction and
 * request validation — the periphery — and skipped the part that produces an
 * answer, because reaching it required a running model.
 *
 * <p>The first test here is the important one. It is the regression guard for
 * the defect that made every other measurement meaningless: the pipeline used
 * to assemble byte-identical evidence for every order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmClientConfig.class)
class AbnormalOrderAnalysisApiTest {

    private static final String MERCHANT_SLOW_ANSWER = """
            {
              "primary_cause": "商家出餐慢",
              "secondary_causes": [],
              "evidence_chain": [
                {"fact": "骑手到店后等待28分钟", "source": "merchant_prep_minutes", "value": "28"}
              ],
              "applicable_rules": ["ANA-001"],
              "suggested_actions": ["向商家发起出餐时效告警"],
              "risk_notes": [],
              "confidence": "HIGH"
            }
            """;

    private static final String ON_TIME_ANSWER = """
            {
              "primary_cause": "无异常",
              "secondary_causes": [],
              "evidence_chain": [
                {"fact": "送达早于承诺时间", "source": "overtime_minutes", "value": "0"}
              ],
              "applicable_rules": ["ANA-006"],
              "suggested_actions": ["向用户说明实际未超时"],
              "risk_notes": [],
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
    }

    @Test
    void buildsDifferentEvidenceForDifferentOrders() throws Exception {
        stubLlmClient.respondWith(MERCHANT_SLOW_ANSWER);

        analyze("TEST001").andExpect(status().isOk());
        analyze("TEST002").andExpect(status().isOk());

        assertEquals(2, stubLlmClient.callCount(), "each request should call the model once");
        String merchantSlowPrompt = stubLlmClient.userPrompts().get(0);
        String capacityShortPrompt = stubLlmClient.userPrompts().get(1);

        assertNotEquals(merchantSlowPrompt, capacityShortPrompt,
                "two different orders must not produce an identical prompt");
        assertTrue(merchantSlowPrompt.contains("TEST001"), "prompt should carry its own order id");
        assertTrue(capacityShortPrompt.contains("TEST002"), "prompt should carry its own order id");
        assertTrue(merchantSlowPrompt.contains("\"S001\""),
                "capacity should be queried for the order's own station");
        assertTrue(capacityShortPrompt.contains("\"S002\""),
                "capacity should be queried for the order's own station");
    }

    @Test
    void reportsTheDeterministicBaselineAlongsideTheModel() throws Exception {
        stubLlmClient.respondWith(MERCHANT_SLOW_ANSWER);

        analyze("TEST001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.output.is_abnormal").value(true))
                .andExpect(jsonPath("$.data.output.baseline_cause").value("商家出餐慢"))
                .andExpect(jsonPath("$.data.output.timeline.merchant_prep_minutes").value(28))
                .andExpect(jsonPath("$.data.output.timeline.overtime_minutes").value(18))
                .andExpect(jsonPath("$.data.output.model_output_parsed").value(true))
                .andExpect(jsonPath("$.data.output.agrees_with_baseline").value(true))
                .andExpect(jsonPath("$.data.output.guardrail_passed").value(true))
                .andExpect(jsonPath("$.data.output.needs_human_review").value(false))
                .andExpect(jsonPath("$.data.output.advisory_only").value(true));
    }

    @Test
    void recognisesAnOrderThatWasNotLate() throws Exception {
        stubLlmClient.respondWith(ON_TIME_ANSWER);

        analyze("TEST005")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.is_abnormal").value(false))
                .andExpect(jsonPath("$.data.output.baseline_cause").value("无异常"))
                .andExpect(jsonPath("$.data.output.timeline.overtime_minutes").value(0))
                .andExpect(jsonPath("$.data.output.agrees_with_baseline").value(true));
    }

    @Test
    void citesTheSeededRuleBase() throws Exception {
        stubLlmClient.respondWith(MERCHANT_SLOW_ANSWER);

        analyze("TEST001")
                .andExpect(status().isOk())
                // Empty before the seed loader existed: nothing ever wrote to
                // the rule base, so every response carried "citations": [].
                .andExpect(jsonPath("$.data.output.citations").isNotEmpty())
                .andExpect(jsonPath("$.data.output.citations[0].source_id").isNotEmpty())
                .andExpect(jsonPath("$.data.output.citations[0].score").isNumber());
    }

    @Test
    void recordsToolNamesAndRealStepDurations() throws Exception {
        stubLlmClient.respondWith(MERCHANT_SLOW_ANSWER);

        analyze("TEST001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[?(@.toolName == 'order_query')]").isNotEmpty())
                .andExpect(jsonPath("$.data.steps[?(@.toolName == 'eta_query')]").isNotEmpty())
                .andExpect(jsonPath("$.data.steps[?(@.toolName == 'capacity_query')]").isNotEmpty())
                .andExpect(jsonPath("$.data.steps[?(@.durationMs < 0)]").isEmpty())
                .andExpect(jsonPath("$.data.totalDurationMs").isNumber());
    }

    @Test
    void asksForReviewWhenTheModelOutputCannotBeParsed() throws Exception {
        stubLlmClient.respondWith("模型今天不想输出 JSON。");

        analyze("TEST001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.model_output_parsed").value(false))
                .andExpect(jsonPath("$.data.output.needs_human_review").value(true))
                .andExpect(jsonPath("$.data.output.review_reasons[?(@ == 'model_output_unparseable')]")
                        .isNotEmpty());
    }

    @Test
    void asksForReviewWhenTheModelDisagreesWithTheBaseline() throws Exception {
        // Baseline for TEST001 is 商家出餐慢; the model blames the weather.
        stubLlmClient.respondWith("""
                {
                  "primary_cause": "天气异常",
                  "secondary_causes": [],
                  "evidence_chain": [],
                  "applicable_rules": ["ANA-003"],
                  "suggested_actions": [],
                  "risk_notes": [],
                  "confidence": "HIGH"
                }
                """);

        analyze("TEST001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.agrees_with_baseline").value(false))
                .andExpect(jsonPath("$.data.output.needs_human_review").value(true))
                .andExpect(jsonPath("$.data.output.review_reasons"
                        + "[?(@ == 'disagrees_with_deterministic_baseline')]").isNotEmpty());
    }

    @Test
    void reportsAGuardrailFailureWithoutHidingTheResult() throws Exception {
        // No primary_cause and a promise-like phrase: both guardrail checks fail.
        stubLlmClient.respondWith("""
                {"summary": "我们承诺尽快处理"}
                """);

        analyze("TEST001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.output.guardrail_passed").value(false))
                .andExpect(jsonPath("$.data.output.needs_human_review").value(true))
                .andExpect(jsonPath("$.data.steps[?(@.stepType == 'guardrail' "
                        + "&& @.status == 'FAILED')]").isNotEmpty());
    }

    @Test
    void returnsBadGatewayWhenTheModelIsUnreachable() throws Exception {
        stubLlmClient.failWith("connection refused");

        analyze("TEST001")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Model service unavailable"));
    }

    private org.springframework.test.web.servlet.ResultActions analyze(String orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/analyze/abnormal-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"order_id\":\"" + orderId + "\"}"));
    }
}
