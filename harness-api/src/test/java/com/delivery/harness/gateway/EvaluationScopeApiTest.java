package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.tool.order.OrderFixtures;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:evaluation-scopes;DB_CLOSE_DELAY=-1",
            "harness.security.demo-identities=" + ScopedRuntimeApiSupport.IDENTITIES
        })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class EvaluationScopeApiTest extends ScopedRuntimeApiSupport {
    @Test
    void evaluationStartDetailsResultsAndListsRequireVisibilityOfEveryFrozenKnowledgeScope()
            throws Exception {
        var runtime = mixedScopeRuntime();
        var order = OrderFixtures.build("DEMO-001", LocalDateTime.of(2026, 1, 1, 12, 0));
        order.setCityCode("010");
        order.setStationId("S001");
        Map<String, Object> sample =
                Map.of(
                        "scenario",
                        "abnormal_order_analysis",
                        "input",
                        Map.of("order_id", "DEMO-001"),
                        "evidence_order",
                        JsonUtil.toMap(order));
        var dataset =
                postJson(
                        "/eval/datasets",
                        Map.of("split", "SYNTHETIC_SMOKE", "samples", List.of(sample)),
                        "scope-owner-token",
                        200);
        String refused =
                JsonUtil.toJson(
                        postJson(
                                "/eval/runs",
                                Map.of(
                                        "dataset_version",
                                        dataset.get("id"),
                                        "runtime_config_id",
                                        runtime.id()),
                                "scope-st1-token",
                                404));
        assertFalse(refused.contains(SECRET));
        var historical =
                store.create(
                        "demo",
                        "eval_run",
                        "mixed-scope-historical-run",
                        Map.of(
                                "status",
                                "COMPLETED",
                                "resolved_config",
                                runtime.data(),
                                "dataset_snapshot",
                                Map.of("samples", List.of(sample))),
                        "owner",
                        "TEST_FROZEN_RUN");
        assertFalse(
                getJson("/eval/runs/" + historical.id(), "scope-st1-token", 404).contains(SECRET));
        assertFalse(
                getJson("/eval/runs/" + historical.id() + "/results", "scope-st1-token", 404)
                        .contains(SECRET));
        String list = getJson("/eval/runs?limit=100", "scope-st1-token", 200);
        assertFalse(list.contains(SECRET));
        assertFalse(list.contains(historical.id()));
        assertTrue(
                getJson("/eval/runs/" + historical.id(), "scope-owner-token", 200)
                        .contains(SECRET));
    }
}
