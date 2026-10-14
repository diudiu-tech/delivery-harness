package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.CaseFilters;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.platform.store.RecordScope;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class CaseQueueFiltersTest {
    private record Scope(String tenantId, Set<String> cityCodes, Set<String> stationIds)
            implements RecordScope {}

    private final Scope actor = new Scope("t", Set.of("*"), Set.of("*"));

    @Test
    void everyQueueFilterIsAppliedBeforeLimit() {
        PlatformStore store = PlatformTestDatabase.memory();
        for (int index = 0; index < 35; index++) {
            store.create(
                    "t",
                    "case",
                    "other-" + index,
                    Map.of(
                            "status",
                            "OPEN",
                            "risk_level",
                            "HIGH",
                            "city_code",
                            "OTHER",
                            "station_id",
                            "OTHER",
                            "complaint_type",
                            "OVERTIME",
                            "order_id",
                            "OTHER",
                            "assignee",
                            "other"),
                    "u",
                    "CREATE");
        }
        store.create(
                "t",
                "case",
                "wanted",
                Map.of(
                        "status",
                        "IN_REVIEW",
                        "risk_level",
                        "NORMAL",
                        "city_code",
                        "CITY",
                        "station_id",
                        "STATION",
                        "complaint_type",
                        "DAMAGE",
                        "order_id",
                        "ORDER",
                        "assignee",
                        "reviewer"),
                "u",
                "CREATE");
        List<CaseFilters> filters =
                List.of(
                        new CaseFilters("IN_REVIEW", null, null, null, null, null, null),
                        new CaseFilters(null, "ORDER", null, null, null, null, null),
                        new CaseFilters(null, null, "NORMAL", null, null, null, null),
                        new CaseFilters(null, null, null, "DAMAGE", null, null, null),
                        new CaseFilters(null, null, null, null, "reviewer", null, null),
                        new CaseFilters(null, null, null, null, null, "CITY", null),
                        new CaseFilters(null, null, null, null, null, null, "STATION"),
                        new CaseFilters(
                                "IN_REVIEW",
                                "ORDER",
                                "NORMAL",
                                "DAMAGE",
                                "reviewer",
                                "CITY",
                                "STATION"));
        for (CaseFilters filter : filters) {
            PlatformStore.Page page = store.caseQueue(actor, filter, "", 1);
            assertEquals(
                    List.of("wanted"),
                    page.items().stream().map(PlatformStore.Entity::id).toList());
            assertEquals("", page.cursor());
        }
        var scoped = new Scope("t", Set.of("OTHER"), Set.of("OTHER"));
        assertTrue(store.caseQueue(scoped, filters.get(7), "", 1).items().isEmpty());
        assertTrue(
                store.caseQueue(new Scope("other", Set.of("*"), Set.of("*")), filters.get(7), "", 1)
                        .items()
                        .isEmpty());
    }

    @Test
    void queueUpdatesRefreshRiskAndAssigneeProjections() {
        PlatformStore store = PlatformTestDatabase.memory();
        var entity =
                store.create(
                        "t",
                        "case",
                        "case",
                        Map.of("risk_level", "HIGH", "assignee", "first"),
                        "u",
                        "CREATE");
        var data = new LinkedHashMap<>(entity.data());
        data.put("risk_level", "NORMAL");
        data.put("assignee", "second");
        store.update("t", "case", entity.id(), entity.version(), data, "u", "CHANGE");
        assertTrue(
                store.caseQueue(
                                actor,
                                new CaseFilters(null, null, "HIGH", null, "first", null, null),
                                "",
                                10)
                        .items()
                        .isEmpty());
        assertEquals(
                "case",
                store.caseQueue(
                                actor,
                                new CaseFilters(null, null, "NORMAL", null, "second", null, null),
                                "",
                                10)
                        .items()
                        .get(0)
                        .id());
        assertThrows(
                PlatformException.class,
                () -> new CaseFilters(null, "x".repeat(121), null, null, null, null, null));
    }

    @Test
    void realFlywayBackfillsLegacyRiskAndAssigneeWithoutChangingEarlierMigrations()
            throws Exception {
        var source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();
        try (var connection = source.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_records"
                                    + "(tenant_id,kind,record_id,record_version,created_at,updated_at,payload)"
                                    + " VALUES ('t','case','legacy',1,?,?,?)")) {
            statement.setObject(1, OffsetDateTime.parse("2026-01-01T00:00:00.120500Z"));
            statement.setObject(2, OffsetDateTime.parse("2026-01-01T00:00:00.120500Z"));
            statement.setString(
                    3, JsonUtil.toJson(Map.of("risk_level", "HIGH", "assignee", "reviewer")));
            statement.executeUpdate();
        }
        PlatformTestDatabase.migrate(source);
        var store = new PlatformStore(source);
        var filter = new CaseFilters(null, null, "HIGH", null, "reviewer", null, null);
        assertEquals("legacy", store.caseQueue(actor, filter, "", 1).items().get(0).id());
    }
}
