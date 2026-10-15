package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.quality.QualityData;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DemoApiTestSupport.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:quality-api-acceptance;DB_CLOSE_DELAY=-1",
            "harness.test.default-bearer=demo-quality-token",
            "harness.security.demo-identities=" + TestIdentityConfig.QUALITY
        })
class QualityAcceptanceApiTest {
    @Autowired MockMvc mvc;

    @Test
    void realVersionManagementAsyncEvaluationReplayAndPublicationAreWired() throws Exception {
        var configs = getJson("/runtime-configs", null, 200);
        assertTrue(configs.get("items") instanceof List<?>);
        var config =
                postJson(
                        "/runtime-configs",
                        Map.of(
                                "model",
                                "fixed-test-model",
                                "prompt",
                                "QA runtime prompt B",
                                "policy_version",
                                "policy-default-v1",
                                "deterministic_only",
                                true),
                        null,
                        200);
        String configId = config.get("id").toString();
        assertEquals("CANDIDATE", QualityData.map(config.get("data")).get("state"));
        var datasets = getJson("/eval/datasets", null, 200);
        assertFalse(((List<?>) datasets.get("items")).isEmpty());
        var queued =
                postJson(
                        "/eval/runs",
                        Map.of(
                                "dataset_version",
                                "synthetic-smoke-v1",
                                "runtime_config_id",
                                configId),
                        null,
                        202);
        String runId = queued.get("id").toString();
        var complete = await(runId);
        Map<String, Object> data = QualityData.map(complete.get("data"));
        assertEquals("COMPLETED", data.get("status"), complete.toString());
        assertEquals(true, data.get("hard_gate_passed"), complete.toString());
        assertEquals(configId, QualityData.map(data.get("resolved_config")).get("config_id"));
        var results = getJson("/eval/runs/" + runId + "/results", null, 200);
        assertEquals(6, ((List<?>) results.get("items")).size());
        var replay = postJson("/eval/runs/" + runId + "/replay", Map.of(), null, 200);
        assertEquals(
                "STORED_OUTPUT_RESCORING", QualityData.map(replay.get("data")).get("replay_mode"));
        var reportBody = Map.of("expected_version", config.get("version"), "eval_run_id", runId);
        postJson("/runtime-configs/" + configId + "/publish", reportBody, null, 403);
        var published =
                postJson(
                        "/runtime-configs/" + configId + "/publish",
                        reportBody,
                        "quality-publisher",
                        200);
        assertEquals("PUBLISHED", QualityData.map(published.get("data")).get("state"));
        assertEquals(
                QualityData.map(config.get("data")).get("config_hash"),
                QualityData.map(published.get("data")).get("config_hash"));
        getJson("/eval/runs/" + runId, "quality-other", 404);
        postJson(
                "/eval/runs",
                Map.of(
                        "dataset_version",
                        "synthetic-smoke-v1",
                        "runtime_config_id",
                        "missing-version"),
                null,
                404);
    }

    @Test
    void knowledgeReferencePublishesAtomicallyAndWithdrawsFromSearch() throws Exception {
        var draft =
                postJson(
                        "/knowledge/versions",
                        Map.of("title", "测试知识", "content", "唯一检索词量化测试"),
                        null,
                        200);
        String id = draft.get("id").toString();
        var reviewed =
                postJson(
                        "/knowledge/versions/" + id + "/review",
                        Map.of("expected_version", draft.get("version")),
                        "quality-publisher",
                        200);
        var published =
                postJson(
                        "/knowledge/versions/" + id + "/publish",
                        Map.of("expected_version", reviewed.get("version")),
                        "quality-publisher",
                        200);
        var found = postJson("/knowledge/search", Map.of("query", "唯一检索词量化测试"), null, 200);
        assertEquals(1, found.get("total_found"));
        var chunk = QualityData.map(((List<?>) found.get("items")).get(0));
        assertTrue(chunk.get("citation_id").toString().startsWith(id + ":"));
        getJson("/knowledge/citations/" + chunk.get("citation_id"), null, 200);
        postJson(
                "/knowledge/versions/" + id + "/withdraw",
                Map.of("expected_version", published.get("version"), "reason", "replaced"),
                "quality-publisher",
                200);
        assertEquals(
                0,
                postJson("/knowledge/search", Map.of("query", "唯一检索词量化测试"), null, 200)
                        .get("total_found"));
        assertEquals(
                "WITHDRAWN",
                getJson("/knowledge/citations/" + chunk.get("citation_id"), null, 200)
                        .get("current_state"));
    }

    @Test
    void personalDataPreviewStaysAvailableButKnowledgePublicationAndRuntimePromptAreBlocked()
            throws Exception {
        Map<String, Object> body =
                Map.of("title", "个人信息样例", "content", "姓名：张三\n手机 13812345678\nperson@example.com");
        var preview = postJson("/knowledge/versions/preview", body, null, 200);
        assertTrue(((List<?>) preview.get("warnings")).contains("POTENTIAL_PHONE_NUMBER"));
        assertTrue(((List<?>) preview.get("warnings")).contains("POTENTIAL_EMAIL_ADDRESS"));
        var draft = postJson("/knowledge/versions", body, null, 200);
        String id = draft.get("id").toString();
        var reviewed =
                postJson(
                        "/knowledge/versions/" + id + "/review",
                        Map.of("expected_version", draft.get("version")),
                        "quality-publisher",
                        200);
        var failure =
                postJson(
                        "/knowledge/versions/" + id + "/publish",
                        Map.of("expected_version", reviewed.get("version")),
                        "quality-publisher",
                        422);
        assertEquals("KNOWLEDGE_CONTAINS_PERSONAL_DATA", failure.get("code"));
        assertFalse(failure.toString().contains("13812345678"));
        assertEquals(
                "REVIEWED",
                QualityData.map(getJson("/knowledge/versions/" + id, null, 200).get("data"))
                        .get("state"));
        var promptFailure =
                postJson(
                        "/runtime-configs",
                        Map.of(
                                "model",
                                "test-model",
                                "prompt",
                                "姓名：张三",
                                "policy_version",
                                "policy-default-v1"),
                        null,
                        422);
        assertEquals("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", promptFailure.get("code"));
    }

    @Test
    void exactIdsUnknownVersionsAndScopedPublishPermissionsAreEnforced() throws Exception {
        getJson("/policies/versions", null, 200);
        var found = postJson("/knowledge/search", Map.of("query", "COMP-001"), null, 200);
        assertEquals(
                true, QualityData.map(((List<?>) found.get("items")).get(0)).get("exact_id_match"));
        postJson(
                "/knowledge/versions",
                Map.of("title", "全局文本", "content", "scope escape"),
                "quality-scoped",
                403);
        postJson(
                "/runtime-configs",
                Map.of("model", "model-B", "prompt", "B", "policy_version", "missing-policy"),
                null,
                404);
        getJson("/runtime-configs?limit=101", null, 400);
    }

    private Map<String, Object> await(String id) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        Map<String, Object> result = null;
        while (System.nanoTime() < end) {
            result = getJson("/eval/runs/" + id, null, 200);
            String state = QualityData.map(result.get("data")).get("status").toString();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state)) {
                return result;
            }
            Thread.sleep(20);
        }
        fail("Evaluation did not complete: " + result);
        return result;
    }

    private Map<String, Object> getJson(String path, String token, int expected) throws Exception {
        var request = get("/api/v2" + path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return JsonUtil.fromJson(
                mvc.perform(request)
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                Map.class);
    }

    private Map<String, Object> postJson(
            String path, Map<String, Object> body, String token, int expected) throws Exception {
        var request =
                post("/api/v2" + path)
                        .contentType("application/json")
                        .content(JsonUtil.toJson(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return JsonUtil.fromJson(
                mvc.perform(request)
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                Map.class);
    }
}
