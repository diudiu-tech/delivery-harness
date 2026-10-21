package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.platform.store.RecordScope;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class IndexedPlatformStoreTest {
    private record Scope(String tenantId, Set<String> cityCodes, Set<String> stationIds)
            implements RecordScope {}

    private final Scope actor = new Scope("t", Set.of("*"), Set.of("*"));

    @Test
    void auditCursorOrdersFractionalTimestampsAndEqualTimeIdsWithoutGaps() throws Exception {
        DataSource source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        PlatformTestDatabase.migrate(source);
        PlatformStore store = new PlatformStore(source);
        List<String> times =
                List.of(
                        "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:00.001Z",
                        "2026-01-01T00:00:00.120Z",
                        "2026-01-01T00:00:00.120500Z",
                        "2026-01-01T00:00:00.120500Z");
        for (int index = 0; index < times.size(); index++) {
            try (var connection = source.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO platform_audit_events(event_id, tenant_id,"
                                            + " actor_id, action, resource_id, created_at, payload)"
                                            + " VALUES (?,?,?,?,?,?,?)")) {
                statement.setString(1, "event-" + index);
                statement.setString(2, "t");
                statement.setString(3, "auditor");
                statement.setString(4, "TEST");
                statement.setString(5, "resource");
                statement.setObject(6, OffsetDateTime.parse(times.get(index)));
                statement.setString(7, "{}");
                statement.executeUpdate();
            }
        }
        List<String> ids = new ArrayList<>();
        String cursor = "";
        do {
            PlatformStore.AuditPage page = store.auditPage(actor, cursor, 1);
            page.items().forEach(event -> ids.add(event.get("event_id").toString()));
            cursor = page.cursor();
        } while (!cursor.isEmpty());
        assertEquals(List.of("event-4", "event-3", "event-2", "event-1", "event-0"), ids);
        assertEquals(
                400,
                assertThrows(PlatformException.class, () -> store.auditPage(actor, "bad", 20))
                        .status());
    }

    @Test
    void indexedCaseAndStatusFilteringRunsBeforeLimitAndDoesNotReadBodies() {
        PlatformStore store = PlatformTestDatabase.memory();
        store.create(
                "t", "case", "case-a", Map.of("city_code", "A", "station_id", "S"), "u", "CREATE");
        store.create(
                "t", "case", "case-b", Map.of("city_code", "B", "station_id", "S"), "u", "CREATE");
        for (int index = 0; index < 130; index++) {
            store.create(
                    "t",
                    "material",
                    String.format("other-%03d", index),
                    Map.of("case_id", "case-b", "status", "UPLOADED"),
                    "u",
                    "CREATE");
        }
        store.create(
                "t",
                "material",
                "visible",
                Map.of("case_id", "case-a", "status", "VERIFIED"),
                "u",
                "CREATE");
        assertEquals(
                "visible",
                store.query("t", "material", "case-a", Set.of("VERIFIED"), null, "", 1)
                        .get(0)
                        .id());
        Scope scoped = new Scope("t", Set.of("A"), Set.of("S"));
        assertEquals(
                "visible",
                store.scopedPage(scoped, "material", null, Set.of(), null, "", 1)
                        .items()
                        .get(0)
                        .id());
        assertTrue(store.query("other", "material", "case-a", Set.of(), null, "", 100).isEmpty());
        assertEquals(1, store.caseRecords("t", "material", "case-a", 100).size());
    }

    @Test
    void scopedReadApisNeverCommitAndRequestReadOnlyConnections() throws Exception {
        DataSource source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        PlatformTestDatabase.migrate(source);
        new PlatformStore(source)
                .create("t", "case", "read-case", Map.of("status", "OPEN"), "u", "CREATE");
        AtomicInteger commits = new AtomicInteger(), readOnly = new AtomicInteger();
        DataSource monitored =
                new DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection delegate = super.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, args) -> {
                                            if ("commit".equals(method.getName()))
                                                commits.incrementAndGet();
                                            if ("setReadOnly".equals(method.getName())
                                                    && Boolean.TRUE.equals(args[0]))
                                                readOnly.incrementAndGet();
                                            try {
                                                return method.invoke(delegate, args);
                                            } catch (InvocationTargetException failure) {
                                                throw failure.getCause();
                                            }
                                        });
                    }
                };
        PlatformStore store = new PlatformStore(monitored);
        assertTrue(store.get("t", "case", "read-case").isPresent());
        store.require("t", "case", "read-case");
        store.query("t", "case", null, Set.of("OPEN"), null, "", 10);
        store.scopedPage(actor, "case", null, Set.of(), null, "", 10);
        store.auditPage(actor, "", 10);
        store.caseQueue(actor, "", 10);
        store.countScoped(actor, "case", null);
        assertEquals(0, commits.get());
        assertEquals(7, readOnly.get());
    }

    @Test
    void schemaContainsCaseStateAndTimestampCursorIndexes() throws Exception {
        DataSource source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        PlatformTestDatabase.migrate(source);
        Set<String> indexes = new HashSet<>();
        try (var connection = source.getConnection();
                var rows =
                        connection
                                .getMetaData()
                                .getIndexInfo(null, null, "PLATFORM_RECORDS", false, false)) {
            while (rows.next())
                if (rows.getString("INDEX_NAME") != null)
                    indexes.add(rows.getString("INDEX_NAME").toLowerCase(Locale.ROOT));
        }
        assertTrue(
                indexes.containsAll(
                        Set.of(
                                "platform_case_children",
                                "platform_status_page",
                                "platform_state_page",
                                "platform_order_cases")));
        try (var connection = source.getConnection();
                var rows =
                        connection
                                .getMetaData()
                                .getColumns(null, null, "PLATFORM_AUDIT_EVENTS", "CREATED_AT")) {
            assertTrue(rows.next());
            assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, rows.getInt("DATA_TYPE"));
        }
    }

    @Test
    void realClasspathFlywayUpgradesLegacyBodiesAndIndexes() throws Exception {
        DataSource source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        try (var connection = source.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_records(tenant_id, kind, record_id,"
                                        + " record_version, created_at, updated_at, payload) VALUES"
                                        + " ('t',?,?,1,?,?,?)")) {
            for (String kind : List.of("case", "material")) {
                statement.setString(1, kind);
                statement.setString(2, kind.equals("case") ? "legacy-case" : "legacy-material");
                statement.setString(3, "2026-01-01T00:00:00.120500Z");
                statement.setString(4, "2026-01-01T00:00:00.001Z");
                statement.setString(
                        5,
                        JsonUtil.toJson(
                                kind.equals("case")
                                        ? Map.of(
                                                "city_code",
                                                "A",
                                                "station_id",
                                                "S",
                                                "order_id",
                                                "O")
                                        : Map.of(
                                                "case_id",
                                                "legacy-case",
                                                "status",
                                                "VERIFIED",
                                                "content_checked",
                                                true,
                                                "content_base64",
                                                "aGVsbG8=")));
                statement.executeUpdate();
            }
        }
        PlatformTestDatabase.migrate(source);
        PlatformStore store = new PlatformStore(source);
        var material = store.caseRecords("t", "material", "legacy-case", 10).get(0);
        assertFalse(material.data().containsKey("content_base64"));
        assertEquals("hello", new String(store.materialContent("t", material.id())));
        assertEquals(
                Instant.parse("2026-01-01T00:00:00.120500Z"), Instant.parse(material.createdAt()));
        Scope scoped = new Scope("t", Set.of("A"), Set.of("S"));
        assertEquals(
                1,
                store.scopedPage(scoped, "material", null, Set.of("VERIFIED"), null, "", 10)
                        .items()
                        .size());
    }
}
