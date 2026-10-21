package com.delivery.harness.gateway;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_ORDER_QUERY;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.gateway.support.StubLlmClient;
import com.delivery.harness.gateway.support.StubLlmClientConfig;
import com.delivery.harness.tool.gateway.ToolGateway;
import com.delivery.harness.tool.order.OrderToolkit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers the two subsystems that used to exist only as read endpoints over empty stores:
 * observability and evaluation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({StubLlmClientConfig.class, DemoApiTestSupport.class})
class ObserveAndEvalApiTest {

    private static final String INVALID_REFERENCES_ANSWER =
            """
            {
              "primary_cause": "商家出餐慢",
              "secondary_causes": [],
              "evidence_chain": [],
              "applicable_rules": ["ANA-001"],
              "suggested_actions": [],
              "risk_notes": [],
              "confidence": "HIGH"
            }
            """;

    private static final String VALID_ON_TIME_ANSWER =
            """
            {
              "primary_cause": "无异常",
              "secondary_causes": [],
              "evidence_chain": [{
                "fact": "送达未超过承诺时间",
                "evidence_id": "timeline_v2",
                "evidence_version": "2",
                "field_path": "overtime_seconds",
                "observed_value": 0,
                "unit": "seconds"
              }],
              "applicable_rules": [],
              "suggested_actions": [],
              "risk_notes": [],
              "confidence": "HIGH"
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    @Autowired private StubLlmClient stubLlmClient;

    @Autowired private ToolGateway toolGateway;

    @Autowired private OrderToolkit orderToolkit;

    @BeforeEach
    void resetStub() {
        stubLlmClient.reset();
        stubLlmClient.respondWith(INVALID_REFERENCES_ANSWER);
    }

    @Test
    void recordsATraceForAnExecutionAndServesItBack() throws Exception {
        // A successful trace needs a valid reference to the matching order's frozen facts.
        // A late order's longest leg alone is not verified responsibility evidence.
        stubLlmClient.respondWith(VALID_ON_TIME_ANSWER);
        MvcResult analysis =
                mockMvc.perform(
                                post("/api/v1/analyze/abnormal-order")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"order_id\":\"TEST005\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                        .andExpect(jsonPath("$.data.output.model_adoptable").value(true))
                        .andExpect(jsonPath("$.data.output.validation_errors").isEmpty())
                        .andExpect(jsonPath("$.data.output.overtime_status").value("ON_TIME"))
                        .andReturn();

        String traceId = analysis.getResponse().getHeader("X-Trace-Id");
        assertNotNull(traceId, "every response carries a trace id");

        // Before AgentOrchestrator recorded traces, this was a 404 for every
        // trace id the API had just handed out.
        mockMvc.perform(get("/api/v1/observe/trace/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(traceId))
                .andExpect(jsonPath("$.data.scenario").value("abnormal_order_analysis"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.spans").isNotEmpty())
                .andExpect(jsonPath("$.data.spans[?(@.spanType == 'tool_call')]").isNotEmpty())
                .andExpect(jsonPath("$.data.spans[?(@.attributes.status == 'FAILED')]").isEmpty());
    }

    @Test
    void countsExecutionsInMetrics() throws Exception {
        stubLlmClient.respondWith(VALID_ON_TIME_ANSWER);
        mockMvc.perform(
                        post("/api/v1/analyze/abnormal-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"order_id\":\"TEST005\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/observe/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data.abnormal_order_analysis_success").isNumber());
    }

    @Test
    void listsRecentTraces() throws Exception {
        mockMvc.perform(
                        post("/api/v1/analyze/abnormal-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"order_id\":\"TEST001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/observe/traces").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    void servesTheSeededEvaluationCases() throws Exception {
        // Empty before EvalSeedLoader: the starter cases lived only in an
        // unexecuted SQL migration.
        mockMvc.perform(get("/api/v1/eval/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.caseId == 'EC-001')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.caseId == 'EC-004')]").isNotEmpty());
    }

    @Test
    void invalidModelReferencesCannotEarnRuleAccuracy() throws Exception {
        MvcResult run =
                mockMvc.perform(
                                post("/api/v1/eval/run")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"caseIds\":[\"EC-001\"],\"modelVersion\":\"stub\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.totalCases").value(1))
                        .andExpect(jsonPath("$.data.completedCases").value(1))
                        .andExpect(jsonPath("$.data.succeededCases").value(1))
                        .andExpect(jsonPath("$.data.failedCases").value(0))
                        .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                        .andReturn();

        String runId =
                objectMapper
                        .readTree(run.getResponse().getContentAsString())
                        .path("data")
                        .path("runId")
                        .asText();

        mockMvc.perform(get("/api/v1/eval/run/{runId}/results", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].caseId").value("EC-001"))
                .andExpect(jsonPath("$.data[0].score.ruleAccuracy").value(0.0))
                .andExpect(jsonPath("$.data[0].score.expertAlignment").value(1.0))
                .andExpect(jsonPath("$.data[0].score.toolExecutionAccuracy").value(1.0))
                .andExpect(jsonPath("$.data[0].traceId").isNotEmpty());
    }

    @Test
    void optionalModelFailureCompletesEvaluationAndRecordsADegradedTrace() throws Exception {
        stubLlmClient.failWith("connection refused");

        MvcResult run =
                mockMvc.perform(
                                post("/api/v1/eval/run")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"caseIds\":[\"EC-001\"]}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                        .andExpect(jsonPath("$.data.totalCases").value(1))
                        .andExpect(jsonPath("$.data.completedCases").value(1))
                        .andExpect(jsonPath("$.data.succeededCases").value(1))
                        .andExpect(jsonPath("$.data.failedCases").value(0))
                        .andReturn();

        String runId =
                objectMapper
                        .readTree(run.getResponse().getContentAsString())
                        .path("data")
                        .path("runId")
                        .asText();
        MvcResult results =
                mockMvc.perform(get("/api/v1/eval/run/{runId}/results", runId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].errorMessage").doesNotExist())
                        .andExpect(jsonPath("$.data[0].traceId").isNotEmpty())
                        .andExpect(jsonPath("$.data[0].actualOutput.degraded").value(true))
                        .andExpect(
                                jsonPath("$.data[0].actualOutput.model_availability")
                                        .value("UNAVAILABLE"))
                        .andExpect(
                                jsonPath("$.data[0].actualOutput.model_output_available")
                                        .value(false))
                        .andExpect(
                                jsonPath("$.data[0].actualOutput.timeline_v2.overtime_seconds")
                                        .value(1080))
                        .andExpect(jsonPath("$.data[0].score.ruleAccuracy").value(0.0))
                        .andExpect(jsonPath("$.data[0].score.expertAlignment").value(0.0))
                        .andExpect(jsonPath("$.data[0].score.toolExecutionAccuracy").value(1.0))
                        .andReturn();

        String traceId =
                objectMapper
                        .readTree(results.getResponse().getContentAsString())
                        .path("data")
                        .get(0)
                        .path("traceId")
                        .asText();
        mockMvc.perform(get("/api/v1/observe/trace/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.output.model_availability").value("UNAVAILABLE"))
                .andExpect(
                        jsonPath(
                                        "$.data.spans[?(@.spanType == 'llm_call'"
                                                + " && @.attributes.status == 'FAILED')]")
                                .isNotEmpty());
    }

    @Test
    void requiredOrderFailureFailsEvaluationAndStillRecordsItsTrace() throws Exception {
        toolGateway.register(
                TOOL_ORDER_QUERY,
                toolGateway.getDefinition(TOOL_ORDER_QUERY),
                parameters ->
                        ToolResult.builder()
                                .toolName(TOOL_ORDER_QUERY)
                                .success(false)
                                .errorMessage("Order source unavailable")
                                .build());
        try {
            MvcResult run =
                    mockMvc.perform(
                                    post("/api/v1/eval/run")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"caseIds\":[\"EC-001\"]}"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.status").value("FAILED"))
                            .andExpect(jsonPath("$.data.completedCases").value(1))
                            .andExpect(jsonPath("$.data.succeededCases").value(0))
                            .andExpect(jsonPath("$.data.failedCases").value(1))
                            .andReturn();
            String runId =
                    objectMapper
                            .readTree(run.getResponse().getContentAsString())
                            .path("data")
                            .path("runId")
                            .asText();
            MvcResult results =
                    mockMvc.perform(get("/api/v1/eval/run/{runId}/results", runId))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data[0].errorMessage").isNotEmpty())
                            .andExpect(jsonPath("$.data[0].traceId").isNotEmpty())
                            .andExpect(jsonPath("$.data[0].score").doesNotExist())
                            .andReturn();
            String traceId =
                    objectMapper
                            .readTree(results.getResponse().getContentAsString())
                            .path("data")
                            .get(0)
                            .path("traceId")
                            .asText();
            mockMvc.perform(get("/api/v1/observe/trace/{traceId}", traceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FAILED"))
                    .andExpect(
                            jsonPath(
                                            "$.data.spans[?(@.attributes.tool_name == 'order_query'"
                                                    + " && @.attributes.status == 'FAILED')]")
                                    .isNotEmpty());
            assertEquals(
                    0,
                    stubLlmClient.callCount(),
                    "required order failure must stop before the model");
        } finally {
            orderToolkit.init();
        }
    }

    @Test
    void aConstantOvertimeAnswerCannotScorePerfectlyOnTheOnTimeCase() throws Exception {
        // EC-004 is an on-time order. A model that always blames overtime is
        // the baseline any real system has to beat, and it must not score 1.0.
        MvcResult run =
                mockMvc.perform(
                                post("/api/v1/eval/run")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"caseIds\":[\"EC-004\"],\"modelVersion\":\"always-overtime\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        String runId =
                objectMapper
                        .readTree(run.getResponse().getContentAsString())
                        .path("data")
                        .path("runId")
                        .asText();

        MvcResult results =
                mockMvc.perform(get("/api/v1/eval/run/{runId}/results", runId))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode score =
                objectMapper
                        .readTree(results.getResponse().getContentAsString())
                        .path("data")
                        .get(0)
                        .path("score");

        assertTrue(
                score.path("overallScore").asDouble() < 1.0,
                "a wrong attribution on the negative case must not score perfectly");
    }
}
