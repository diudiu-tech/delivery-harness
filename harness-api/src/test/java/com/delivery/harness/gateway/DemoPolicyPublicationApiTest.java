package com.delivery.harness.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.util.JsonUtil;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

@SpringBootTest(
        properties = "spring.datasource.url=jdbc:h2:mem:public-demo-policy;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DemoPolicyPublicationApiTest {
    @Autowired MockMvc mvc;

    @Test
    void actualProfilePublisherCanPublishAnotherAuthorsPolicyWhileSelfPublicationIsRefused()
            throws Exception {
        Map<String, Object> rule =
                Map.of(
                        "ruleId",
                        "DEMO-PUBLICATION",
                        "ruleName",
                        "Synthetic test",
                        "ruleType",
                        "compensation",
                        "conditions",
                        Map.of("complaint_type", "OVERTIME", "min_overtime_minutes", 0),
                        "actions",
                        Map.of("amount_type", "FIXED", "amount", "3.00", "method", "COUPON"));
        String response =
                mvc.perform(
                                post("/api/v2/policies/versions")
                                        .header("Host", "localhost:8080")
                                        .header("Authorization", "Bearer demo-admin-token")
                                        .contentType("application/json")
                                        .content(
                                                JsonUtil.toJson(
                                                        Map.of(
                                                                "name",
                                                                "Synthetic demo policy",
                                                                "rules",
                                                                List.of(rule)))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Map<String, Object> policy = JsonUtil.fromJson(response, Map.class);
        String path = "/api/v2/policies/versions/" + policy.get("id") + "/publish";
        String body = JsonUtil.toJson(Map.of("expected_version", policy.get("version")));
        mvc.perform(
                        post(path)
                                .header("Host", "localhost:8080")
                                .header("Authorization", "Bearer demo-admin-token")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SEPARATION_OF_DUTIES"));
        mvc.perform(
                        post(path)
                                .header("Host", "localhost:8080")
                                .header("Authorization", "Bearer demo-publisher-token")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PUBLISHED"));
    }
}
