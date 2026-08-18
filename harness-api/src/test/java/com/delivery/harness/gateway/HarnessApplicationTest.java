package com.delivery.harness.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HarnessApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoadsAndExposesMetricsEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/observe/metrics"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsAnalysisWithoutOrderId() throws Exception {
        mockMvc.perform(post("/api/v1/analyze/abnormal-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void ingestsAndFindsSyntheticKnowledge() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Synthetic policy\",\"sourceType\":\"document\","
                                + "\"category\":\"test\",\"content\":\"synthetic-needle policy text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("Synthetic policy"));

        mockMvc.perform(post("/api/v1/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"synthetic-needle\",\"sourceTypes\":[\"document\"],\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFound").value(1))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("document"));
    }

    @Test
    void rejectsChunkOverlapThatCannotAdvance() throws Exception {
        String content = "x".repeat(200);
        String request = String.format(
                "{\"title\":\"Invalid chunks\",\"sourceType\":\"document\","
                        + "\"content\":\"%s\",\"chunkSize\":100,\"chunkOverlap\":100}",
                content);

        mockMvc.perform(post("/api/v1/knowledge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsChunkOverlapWithoutChunkSize() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Invalid chunks\",\"sourceType\":\"document\","
                                + "\"content\":\"policy text\",\"chunkOverlap\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsEvalCaseWithoutRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/eval/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsNullEvalCaseIdElement() throws Exception {
        mockMvc.perform(post("/api/v1/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void returnsHttpNotFoundForMissingEvalRun() throws Exception {
        mockMvc.perform(get("/api/v1/eval/run/missing-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void returnsHttpNotFoundForMissingTrace() throws Exception {
        mockMvc.perform(get("/api/v1/observe/trace/missing-trace"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
