package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.PlatformStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

class EvaluationResultSummaryTest {
    private static final String KIND = "eval_result:run-1";

    @Test
    void scoreMetadataIsProjectedWithoutReadingFrozenEvidenceOrExecution() throws Exception {
        DataSource source = source();
        PlatformTestDatabase.migrate(source);
        var store = new PlatformStore(source);
        store.create("t", KIND, "result", resultData(), "quality", "EVAL_SAMPLE_COMPLETED");
        corruptPayload(source);
        assertScoreSummary(store);
        assertTrue(store.summaryPage("other-tenant", KIND, "", 20).isEmpty());
        assertTrue(store.summaryPage("t", "eval_result:other-run", "", 20).isEmpty());
    }

    @Test
    void migrationRestoresExistingResultSummariesWithoutChangingImmutablePayloads()
            throws Exception {
        DataSource source = source();
        Flyway.configure().dataSource(source).target("9").load().migrate();
        var store = new PlatformStore(source);
        var original =
                store.create("t", KIND, "result", resultData(), "quality", "EVAL_SAMPLE_COMPLETED");
        store.create(
                "t", "knowledge", "unrelated", Map.of("title", "Keep me"), "quality", "CREATED");
        try (var connection = source.getConnection();
                var update = connection.createStatement()) {
            // Emulate the V9 projection, which omitted every result-specific field.
            update.executeUpdate(
                    "UPDATE platform_records SET summary_payload='{}' WHERE"
                        + " kind='eval_result:run-1'");
            // V10 must not deserialize unrelated kinds while repairing results.
            update.executeUpdate(
                    "UPDATE platform_records SET payload='not-json' WHERE kind='knowledge'");
        }
        PlatformTestDatabase.migrate(source);
        var restored = store.require("t", KIND, "result");
        assertEquals(original.version(), restored.version());
        assertEquals(original.updatedAt(), restored.updatedAt());
        assertEquals(JsonUtil.toJson(original.data()), JsonUtil.toJson(restored.data()));
        assertEquals(
                "Keep me", store.summaryPage("t", "knowledge", "", 20).get(0).data().get("title"));
        corruptPayload(source);
        assertScoreSummary(store);
    }

    private static DataSource source() {
        return PlatformTestDatabase.source(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private static Map<String, Object> resultData() {
        var scores =
                Map.of(
                        "scorer_version",
                        "exact-fields-v1",
                        "measurement_status",
                        "NOT_MEASURED",
                        "measured_dimensions",
                        0,
                        "hard_gate_passed",
                        false,
                        "hard_failures",
                        List.of("EXECUTION_NOT_COMPLETED"),
                        "measurements",
                        Map.of(
                                "fields",
                                Map.of("status", "NOT_MEASURED", "actual", "private-label")));
        return Map.of(
                "run_id",
                "run-1",
                "sample",
                Map.of(
                        "sample_id",
                        "sample-1",
                        "evidence_order",
                        Map.of("private-body", "x".repeat(100_000))),
                "execution",
                Map.of("status", "FAILED", "output", Map.of("private-output", "redacted")),
                "scores",
                scores,
                "baseline_scores",
                scores,
                "replay_equal",
                true);
    }

    private static void corruptPayload(DataSource source) throws Exception {
        try (var connection = source.getConnection();
                var update = connection.createStatement()) {
            update.executeUpdate(
                    "UPDATE platform_records SET payload='not-json' WHERE"
                        + " kind='eval_result:run-1'");
        }
    }

    private static void assertScoreSummary(PlatformStore store) {
        var summary = store.summaryPage("t", KIND, "", 20).get(0).data();
        assertEquals("sample-1", summary.get("sample_id"));
        assertEquals("run-1", summary.get("run_id"));
        assertEquals("FAILED", summary.get("execution_status"));
        assertEquals(true, summary.get("replay_equal"));
        for (String key : List.of("scores", "baseline_scores")) {
            var score = (Map<?, ?>) summary.get(key);
            assertEquals("NOT_MEASURED", score.get("measurement_status"));
            assertEquals(false, score.get("hard_gate_passed"));
            assertEquals(List.of("EXECUTION_NOT_COMPLETED"), score.get("hard_failures"));
        }
        assertFalse(summary.containsKey("sample"));
        assertFalse(summary.containsKey("execution"));
        assertFalse(JsonUtil.toJson(summary).contains("private-"));
    }
}
