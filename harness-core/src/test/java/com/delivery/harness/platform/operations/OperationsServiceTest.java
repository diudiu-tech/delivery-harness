package com.delivery.harness.platform.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.auth.AuthContext.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

class OperationsServiceTest {
    @Test
    void knownCostAndUnknownSamplesStaySeparateAndFeedbackIsScopedInAudit() throws Exception {
        var store = PlatformTestDatabase.memory();
        var actor =
                new Principal(
                        "test",
                        "operator",
                        Set.of("AGENT", "OPERATOR", "AUDITOR"),
                        Set.of("*"),
                        Set.of("*"));
        var c =
                store.create(
                        "test",
                        "case",
                        "case-1",
                        Map.of(
                                "city_code",
                                "CITY",
                                "station_id",
                                "STATION",
                                "order_id",
                                "DEMO-002"),
                        actor.userId(),
                        "CASE_CREATED");
        store.create(
                "test",
                "execution",
                "known",
                Map.of(
                        "case_id",
                        c.id(),
                        "status",
                        "SUCCESS",
                        "duration_ms",
                        100,
                        "model_call_metadata",
                        Map.of(
                                "cost_status",
                                "KNOWN",
                                "cost_currency",
                                "CNY",
                                "price_version",
                                "v1",
                                "estimated_cost",
                                "0.012")),
                "worker",
                "EXECUTION");
        store.create(
                "test",
                "execution",
                "unknown",
                Map.of("case_id", c.id(), "status", "DEGRADED", "duration_ms", 300),
                "worker",
                "EXECUTION");
        store.create(
                "test",
                "feedback",
                "correction",
                Map.of("case_id", c.id()),
                "operator",
                "FEEDBACK_SUBMITTED");
        var scoped =
                new Principal(
                        "test",
                        "auditor",
                        Set.of("AUDITOR", "OPERATOR"),
                        Set.of(c.data().get("city_code").toString()),
                        Set.of(c.data().get("station_id").toString()));
        store.create(
                "test",
                "execution",
                "incomplete-price",
                Map.of(
                        "case_id",
                        c.id(),
                        "status",
                        "SUCCESS",
                        "model_call_metadata",
                        Map.of("cost_status", "KNOWN", "estimated_cost", "0.50")),
                "worker",
                "EXECUTION");
        var database = (DataSource) ReflectionTestUtils.getField(store, "source");
        try (var connection = database.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE platform_records SET payload='not-json' WHERE"
                                    + " kind='execution'")) {
            statement.executeUpdate();
        }
        var service = new OperationsService(store);
        var metrics = service.metrics(scoped);
        var cost = (Map<?, ?>) metrics.get("model_cost");
        assertEquals("PARTIALLY_KNOWN", cost.get("status"));
        assertEquals(1L, cost.get("known_executions"));
        assertEquals(2L, cost.get("unknown_executions"));
        assertEquals(
                new BigDecimal("0.012"),
                ((Map<?, ?>) cost.get("totals_by_currency_and_price_version")).get("CNY:v1"));
        assertEquals(300L, ((Map<?, ?>) metrics.get("latency")).get("p95_ms"));
        assertTrue(
                ((List<?>) service.audit(scoped, 100, null).get("items"))
                        .stream()
                                .anyMatch(
                                        e ->
                                                e instanceof Map<?, ?> event
                                                        && "correction"
                                                                .equals(event.get("target"))));
    }
}
