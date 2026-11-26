package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.platform.store.RecordScope;
import com.delivery.harness.platform.store.RecordSummaries;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class RecordSummaryStoreTest {
    private record Scope(String tenantId, Set<String> cityCodes, Set<String> stationIds)
            implements RecordScope {}

    @Test
    void projectedQueriesDoNotReadOrDeserializeTheFullSnapshot() throws Exception {
        var source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        PlatformTestDatabase.migrate(source);
        var store = new PlatformStore(source);
        var cost =
                Map.of(
                        "cost_status",
                        "KNOWN",
                        "estimated_cost",
                        new BigDecimal("0.12"),
                        "cost_currency",
                        "CNY",
                        "price_version",
                        "v1",
                        "private_body",
                        "secret");
        var row =
                store.create(
                        "t",
                        "execution",
                        "run",
                        Map.of(
                                "status",
                                "SUCCESS",
                                "city_code",
                                "C1",
                                "station_id",
                                "S1",
                                "duration_ms",
                                123,
                                "model_call_metadata",
                                cost,
                                "runtime_config",
                                Map.of("knowledge_snapshot", "x".repeat(1_000_000))),
                        "a",
                        "CREATED");
        // A projection that loads payload and strips it in Java cannot pass this corruption probe.
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE platform_records SET payload='not-json' WHERE"
                                        + " record_id=?")) {
            statement.setString(1, row.id());
            statement.executeUpdate();
        }
        var actor = new Scope("t", Set.of("C1"), Set.of("S1"));
        var result = store.scopedSummaryRecent(actor, "execution", 1000);
        assertEquals(1, result.size());
        assertEquals(123, result.get(0).data().get("duration_ms"));
        assertFalse(result.get(0).data().containsKey("runtime_config"));
        assertFalse(JsonUtil.toJson(result).contains("private_body"));
        assertEquals(1, store.summaryPage("t", "execution", "", 10).size());
        assertEquals(
                1,
                store.scopedSummaryPage(actor, "execution", null, Set.of(), null, "", 10)
                        .items()
                        .size());
        assertTrue(
                store.scopedSummaryRecent(
                                new Scope("t", Set.of("C2"), Set.of("S2")), "execution", 10)
                        .isEmpty());
    }

    @Test
    void migrationBackfillsLegacySummariesWithoutCopyingDocuments() throws Exception {
        var source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(source).target("7").load().migrate();
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "INSERT INTO"
                                    + " platform_records(tenant_id,kind,record_id,record_version,"
                                    + "created_at,updated_at,payload)"
                                    + " VALUES"
                                    + " ('t','knowledge','k',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)")) {
            statement.setString(
                    1,
                    JsonUtil.toJson(
                            Map.of(
                                    "title",
                                    "Policy",
                                    "content",
                                    "private-document",
                                    "city_codes",
                                    List.of("C1"),
                                    "station_ids",
                                    List.of("S1"))));
            statement.executeUpdate();
        }
        PlatformTestDatabase.migrate(source);
        var summary = new PlatformStore(source).summaryPage("t", "knowledge", "", 10).get(0).data();
        assertEquals("Policy", summary.get("title"));
        assertEquals(List.of("S1"), summary.get("station_ids"));
        assertFalse(summary.containsKey("content"));
    }

    @Test
    void runtimeAndEvaluationProjectionRetainsEveryScopeWithoutSnapshotContent() {
        var scope = Map.of("city_codes", List.of("C1"), "station_ids", List.of("S2"));
        var config =
                Map.<String, Object>of(
                        "policy_scope",
                        scope,
                        "knowledge_snapshot",
                        List.of(
                                Map.of(
                                        "city_codes",
                                        List.of("C1"),
                                        "station_ids",
                                        List.of("S3"),
                                        "content",
                                        "private-knowledge")),
                        "system_prompt",
                        "private-prompt");
        assertEquals(
                false,
                RecordSummaries.of("knowledge", Map.of("station_ids", "invalid"))
                        .get("summary_scope_valid"));
        var summary = RecordSummaries.of("runtime_config", config);
        assertEquals(
                List.of(Map.of("city_codes", List.of("C1"), "station_ids", List.of("S3"))),
                summary.get("knowledge_scopes"));
        assertFalse(JsonUtil.toJson(summary).contains("private-"));
        var sample = Map.of("evidence_order", Map.of("cityCode", "C1", "stationId", "S4"));
        var run =
                RecordSummaries.of(
                        "eval_run",
                        Map.of(
                                "resolved_config",
                                config,
                                "dataset_snapshot",
                                Map.of("samples", List.of(sample))));
        assertEquals(
                List.of(Map.of("city_code", "C1", "station_id", "S4")), run.get("evidence_scopes"));
        assertEquals(
                summary.get("knowledge_scopes"),
                ((Map<?, ?>) run.get("runtime_visibility")).get("knowledge_scopes"));
        assertEquals(
                List.of(Map.of()),
                RecordSummaries.of("eval_dataset", Map.of()).get("evidence_scopes"));
    }
}
