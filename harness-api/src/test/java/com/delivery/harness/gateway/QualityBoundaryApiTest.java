package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.quality.QualityData;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DemoApiTestSupport.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:quality-boundary;DB_CLOSE_DELAY=-1",
            "harness.test.default-bearer=demo-quality-token",
            "harness.security.demo-identities=" + TestIdentityConfig.QUALITY
        })
class QualityBoundaryApiTest {
    @Autowired MockMvc mvc;
    @Autowired PlatformStore store;

    @Test
    void rejectedPrivateValuesNeverAppearInValidationResponse() throws Exception {
        String privateText = "private-evidence-13812345678".repeat(20);
        Map<String, Object> response =
                postJson(
                        "/knowledge/versions",
                        Map.of("title", privateText, "content", "safe reference"),
                        400);
        assertEquals("INVALID_REQUEST", response.get("code"));
        assertEquals("Invalid request", response.get("message"));
        assertFalse(response.toString().contains("13812345678"));
        assertFalse(response.toString().contains("rejectedValue"));
    }

    @Test
    void scalarTypesListsAndNestingHaveExplicitRequestBoundaries() throws Exception {
        postJson("/knowledge/versions", Map.of("title", 42, "content", "reference"), 400);
        postJson(
                "/knowledge/versions",
                Map.of("title", "title", "content", "reference", "tags", List.of(42)),
                400);
        postJson(
                "/knowledge/versions",
                Map.of("title", "title", "content", "reference", "effective_from", 42),
                400);
        postJson("/knowledge/search", Map.of("query", "overtime", "top_k", "10"), 400);
        postJson("/knowledge/search", Map.of("query", "overtime", "top_k", 1.9), 400);
        postJson("/knowledge/search", Map.of("query", "overtime", "scan_limit", 1001), 400);
        postJson(
                "/runtime-configs",
                Map.of(
                        "model",
                        "test",
                        "prompt",
                        "reference",
                        "policy_version",
                        "policy-default-v1",
                        "deterministic_only",
                        "true"),
                400);
        postJson(
                "/runtime-configs",
                Map.of(
                        "model",
                        "test",
                        "prompt",
                        "reference",
                        "policy_version",
                        "policy-default-v1",
                        "temperature",
                        "0.5"),
                400);
        postJson(
                "/feedback",
                Map.of(
                        "case_id",
                        "case",
                        "decision_id",
                        "decision",
                        "decision_version",
                        "1",
                        "category",
                        "AMOUNT_ERROR",
                        "correction",
                        Map.of("reason_code", "verified")),
                400);
    }

    @Test
    void typedMetadataUsedByWorkbenchSurvivesPersistence() throws Exception {
        Map<String, Object> knowledge =
                postJson(
                        "/knowledge/versions",
                        Map.of(
                                "title",
                                "Named reference",
                                "content",
                                "safe reference",
                                "source_type",
                                "case",
                                "tags",
                                List.of("overtime")),
                        200);
        Map<String, Object> reference = QualityData.map(knowledge.get("data"));
        assertEquals("case", reference.get("source_type"));
        assertEquals("REFERENCE_ONLY", reference.get("trust_level"));
        assertEquals(List.of("overtime"), reference.get("tags"));
        Map<String, Object> config =
                postJson(
                        "/runtime-configs",
                        Map.of(
                                "name",
                                "Named config",
                                "model",
                                "test",
                                "prompt",
                                "safe reference",
                                "policy_version",
                                "policy-default-v1",
                                "deterministic_only",
                                true),
                        200);
        assertEquals("Named config", QualityData.map(config.get("data")).get("name"));
        Map<String, Object> dataset =
                postJson(
                        "/eval/datasets",
                        Map.of(
                                "name",
                                "Named dataset",
                                "split",
                                "SYNTHETIC_SMOKE",
                                "samples",
                                List.of(
                                        Map.of(
                                                "scenario",
                                                "abnormal_order_analysis",
                                                "input",
                                                Map.of("order_id", "DEMO-001")))),
                        200);
        assertEquals("Named dataset", QualityData.map(dataset.get("data")).get("name"));
    }

    @Test
    void repeatedSuccessfulQualityReadsDoNotWriteSeedRowsOrAudits() throws Exception {
        int audits = store.audits("demo").size();
        List<String> paths =
                List.of(
                        "/policies/versions",
                        "/policies/versions/policy-default-v1",
                        "/knowledge/versions",
                        "/runtime-configs",
                        "/runtime-configs/runtime-default-v1",
                        "/runtime-routes",
                        "/eval/datasets",
                        "/eval/datasets/synthetic-smoke-v1",
                        "/eval/runs",
                        "/feedback");
        for (int repeat = 0; repeat < 2; repeat++) {
            for (String path : paths) {
                mvc.perform(get("/api/v2" + path)).andExpect(status().isOk());
            }
        }
        assertEquals(audits, store.audits("demo").size());
    }

    private Map<String, Object> postJson(String path, Map<String, Object> body, int expected)
            throws Exception {
        String response =
                mvc.perform(
                                post("/api/v2" + path)
                                        .contentType("application/json")
                                        .content(JsonUtil.toJson(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonUtil.fromJson(response, Map.class);
    }
}
