package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.quality.KnowledgeService;
import com.delivery.harness.platform.quality.QualityData;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

/** Withdrawal retires content across its whole scope, so it needs authoring authority over it. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:withdrawal-scopes;DB_CLOSE_DELAY=-1",
            "harness.security.demo-identities=" + WithdrawalScopeApiTest.IDENTITIES
        })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class WithdrawalScopeApiTest extends ScopedRuntimeApiSupport {
    static final String IDENTITIES =
            "[{\"token_sha256\":"
                    + "\"1540b9299becfb024201bc50b89774bc5f14e36f7d804fcb474529041323d097\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"global-owner\","
                    + "\"roles\":[\"QUALITY\",\"POLICY_EDITOR\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},"
                    + "{\"token_sha256\":"
                    + "\"058dcba571513a16593a1c0f362892420712b6d66f37bd1ad4389f46f2555865\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"global-publisher\","
                    + "\"roles\":[\"POLICY_PUBLISHER\"],"
                    + "\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},"
                    + "{\"token_sha256\":"
                    + "\"879d80a1150a65c4dd151d30d2d18b7516b1fd03794a1a9a4fd9d0b371e5af24\","
                    + "\"tenant_id\":\"demo\",\"user_id\":\"city-publisher\","
                    + "\"roles\":[\"POLICY_PUBLISHER\"],"
                    + "\"city_codes\":[\"010\"],\"station_ids\":[\"S001\"]}]";
    private static final String OWNER = "global-owner-withdraw-token";
    private static final String GLOBAL = "global-publisher-withdraw-token";
    private static final String CITY = "city-publisher-withdraw-token";

    @Test
    void scopedPublishersCannotWithdrawTenantWideContent() throws Exception {
        // Seeds the default policy and runtime config for the demo tenant.
        getJson("/runtime-configs?limit=1", GLOBAL, 200);
        Map<String, Object> route =
                postJson(
                        "/runtime-routes",
                        Map.of(
                                "scenario",
                                "compensation_suggestion",
                                "primary_config_id",
                                "runtime-default-v1",
                                "fallback_config_id",
                                "runtime-default-v1",
                                "rollout_percent",
                                100),
                        GLOBAL,
                        200);
        String routeId = route.get("id").toString();
        postJson("/runtime-routes/" + routeId + "/withdraw", withdrawal(route), CITY, 403);
        Map<String, Object> config =
                QualityData.map(
                        JsonUtil.fromJson(
                                getJson("/runtime-configs/runtime-default-v1", CITY, 200),
                                Map.class));
        postJson(
                "/runtime-configs/runtime-default-v1/withdraw", withdrawal(config), CITY, 403);
        Map<String, Object> policy =
                QualityData.map(
                        JsonUtil.fromJson(
                                getJson("/policies/versions/policy-default-v1", CITY, 200),
                                Map.class));
        postJson(
                "/policies/versions/policy-default-v1/withdraw", withdrawal(policy), CITY, 403);

        Map<String, Object> draft =
                postJson(
                        "/knowledge/versions",
                        Map.of("title", "Tenant-wide reference", "content", "global reference"),
                        OWNER,
                        200);
        String knowledgeId = draft.get("id").toString();
        Map<String, Object> reviewed =
                postJson(
                        "/knowledge/versions/" + knowledgeId + "/review",
                        Map.of("expected_version", draft.get("version")),
                        GLOBAL,
                        200);
        Map<String, Object> published =
                postJson(
                        "/knowledge/versions/" + knowledgeId + "/publish",
                        Map.of("expected_version", reviewed.get("version")),
                        GLOBAL,
                        200);
        postJson(
                "/knowledge/versions/" + knowledgeId + "/withdraw",
                withdrawal(published),
                CITY,
                403);

        // The tenant-wide publisher keeps full authority, and nothing above changed state.
        assertEquals(
                "PUBLISHED",
                store.require("demo", KnowledgeService.KIND, knowledgeId).data().get("state"));
        Map<String, Object> withdrawn =
                postJson("/runtime-routes/" + routeId + "/withdraw", withdrawal(route), GLOBAL, 200);
        assertEquals("WITHDRAWN", QualityData.map(withdrawn.get("data")).get("state"));
        postJson(
                "/knowledge/versions/" + knowledgeId + "/withdraw",
                withdrawal(published),
                GLOBAL,
                200);
    }

    private static Map<String, Object> withdrawal(Map<String, Object> entity) {
        return Map.of("expected_version", entity.get("version"), "reason", "scope check");
    }
}
