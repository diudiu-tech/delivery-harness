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
    void contextLoadsAndExposesMetricsAndActivePolicy() throws Exception {
        mockMvc.perform(get("/api/observe/metrics"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/policies/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bundle.policyVersion").value("policy-v1.0.0"))
                .andExpect(jsonPath("$.data.policyHash").isNotEmpty())
                .andExpect(jsonPath("$.data.renderedRules").isNotEmpty());
    }

    @Test
    void rejectsCaseCreationWithoutOrderIdOrWithInvalidComplaintType() throws Exception {
        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-MERCHANT-01\",\"complaint_type\":\"NOT_A_TYPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void hidesDemoFixturesOutsideDemoProfile() throws Exception {
        mockMvc.perform(get("/api/demo/fixtures"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundForMissingCaseAndTrace() throws Exception {
        mockMvc.perform(get("/api/cases/CASE-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/api/observe/traces/missing-trace"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
