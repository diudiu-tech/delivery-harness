package com.delivery.harness.gateway;

import com.delivery.harness.gateway.support.StubLlmClient;
import com.delivery.harness.gateway.support.StubLlmClientConfig;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the two subsystems that used to exist only as read endpoints over
 * empty stores: observability and evaluation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmClientConfig.class)
class ObserveAndEvalApiTest {

    private static final String ANSWER = """
            {"primary_cause": "商家出餐慢", "applicable_rules": ["ANA-001"], "confidence": "HIGH"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubLlmClient stubLlmClient;

    @BeforeEach
    void resetStub() {
        stubLlmClient.reset();
        stubLlmClient.respondWith(ANSWER);
    }

    @Test
    void recordsATraceForAnExecutionAndServesItBack() throws Exception {
        MvcResult analysis = mockMvc.perform(post("/api/v1/analyze/abnormal-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"TEST001\"}"))
                .andExpect(status().isOk())
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
                .andExpect(jsonPath("$.data.spans[?(@.spanType == 'tool_call')]").isNotEmpty());
    }

    @Test
    void countsExecutionsInMetrics() throws Exception {
        mockMvc.perform(post("/api/v1/analyze/abnormal-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"TEST001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/observe/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data.abnormal_order_analysis_success").isNumber());
    }

    @Test
    void listsRecentTraces() throws Exception {
        mockMvc.perform(post("/api/v1/analyze/abnormal-order")
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
    void scoresAnEvaluationRunAgainstTheSeededCases() throws Exception {
        MvcResult run = mockMvc.perform(post("/api/v1/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[\"EC-001\"],\"modelVersion\":\"stub\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCases").value(1))
                .andExpect(jsonPath("$.data.completedCases").value(1))
                .andReturn();

        String runId = objectMapper.readTree(run.getResponse().getContentAsString())
                .path("data").path("runId").asText();

        mockMvc.perform(get("/api/v1/eval/run/{runId}/results", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].caseId").value("EC-001"))
                .andExpect(jsonPath("$.data[0].score.ruleAccuracy").value(1.0))
                .andExpect(jsonPath("$.data[0].score.expertAlignment").value(1.0))
                .andExpect(jsonPath("$.data[0].score.toolExecutionAccuracy").value(1.0));
    }

    @Test
    void aConstantOvertimeAnswerCannotScorePerfectlyOnTheOnTimeCase() throws Exception {
        // EC-004 is an on-time order. A model that always blames overtime is
        // the baseline any real system has to beat, and it must not score 1.0.
        MvcResult run = mockMvc.perform(post("/api/v1/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[\"EC-004\"],\"modelVersion\":\"always-overtime\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String runId = objectMapper.readTree(run.getResponse().getContentAsString())
                .path("data").path("runId").asText();

        MvcResult results = mockMvc.perform(get("/api/v1/eval/run/{runId}/results", runId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode score = objectMapper.readTree(results.getResponse().getContentAsString())
                .path("data").get(0).path("score");

        assertTrue(score.path("overallScore").asDouble() < 1.0,
                "a wrong attribution on the negative case must not score perfectly");
    }
}
