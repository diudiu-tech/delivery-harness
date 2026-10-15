package com.delivery.harness.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.PlatformStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

abstract class ScopedRuntimeApiSupport {
    protected static final String IDENTITIES =
            "[{\"token_sha256\":\"a3ddc5159e2bacff172b9f8e2a54e950eaf263b0b4fc224bf2aea722a7f064"
                + "9e\",\"tenant_id\":\"demo\",\"user_id\":\"owner\",\"roles\":[\"QUALITY\",\"POLICY_EDITOR\"],\"ci"
                + "ty_codes\":[\"*\"],\"station_ids\":[\"*\"]},{\"token_sha256\":\"8aafd6924eeb94fc8fea3f7c4a"
                + "a7cf681caa0d1adee3d302af5014bb53cc842a\",\"tenant_id\":\"demo\",\"user_id\":\"publisher\""
                + ",\"roles\":[\"QUALITY\",\"POLICY_PUBLISHER\"],\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},"
                + "{\"token_sha256\":\"c4838eef988843e438137d742ae55f6d7963ebf91b61128f670459604fc3fff"
                + "1\",\"tenant_id\":\"demo\",\"user_id\":\"station-one\",\"roles\":[\"AGENT\",\"QUALITY\"],\"city_"
                + "codes\":[\"010\"],\"station_ids\":[\"S001\"]},{\"token_sha256\":\"c6ce6c14ac16b822f62063f9"
                + "ecd3c9236c34943549923cddbd53828a19427f6d\",\"tenant_id\":\"demo\",\"user_id\":\"station-"
                + "two\",\"roles\":[\"AGENT\",\"QUALITY\"],\"city_codes\":[\"010\"],\"station_ids\":[\"S002\"]}]";
    protected static final String SECRET = "station-two-confidential-reference";
    @Autowired protected MockMvc mvc;
    @Autowired protected PlatformStore store;

    protected PlatformStore.Entity mixedScopeRuntime() throws Exception {
        Map<String, Object> draft =
                postJson(
                        "/knowledge/versions",
                        Map.of(
                                "title",
                                "Station two reference",
                                "content",
                                SECRET,
                                "city_codes",
                                List.of("010"),
                                "station_ids",
                                List.of("S002")),
                        "scope-owner-token",
                        200);
        String knowledgeId = draft.get("id").toString();
        Map<String, Object> reviewed =
                postJson(
                        "/knowledge/versions/" + knowledgeId + "/review",
                        Map.of("expected_version", draft.get("version")),
                        "scope-publisher-token",
                        200);
        postJson(
                "/knowledge/versions/" + knowledgeId + "/publish",
                Map.of("expected_version", reviewed.get("version")),
                "scope-publisher-token",
                200);
        Map<String, Object> runtime =
                postJson(
                        "/runtime-configs",
                        Map.of(
                                "model",
                                "fixed",
                                "prompt",
                                "Use verified facts",
                                "policy_version",
                                "policy-default-v1",
                                "deterministic_only",
                                true,
                                "knowledge_versions",
                                List.of(knowledgeId)),
                        "scope-owner-token",
                        200);
        return store.require("demo", "runtime_config", runtime.get("id").toString());
    }

    protected Map<String, Object> postJson(
            String path, Map<String, Object> body, String token, int expected) throws Exception {
        String response =
                mvc.perform(
                                post("/api/v2" + path)
                                        .header("Host", "localhost:8080")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content(JsonUtil.toJson(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonUtil.fromJson(response, Map.class);
    }

    protected String getJson(String path, String token, int expected) throws Exception {
        return mvc.perform(
                        get("/api/v2" + path)
                                .header("Host", "localhost:8080")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
