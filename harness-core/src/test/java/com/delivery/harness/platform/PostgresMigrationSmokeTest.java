package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.execution.DemoExecutionRuntimeProvider;
import com.delivery.harness.platform.execution.ExecutionService;
import com.delivery.harness.platform.store.CaseFilters;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Runs the real classpath migration chain and the JDBC store on real PostgreSQL binaries. H2
 * accepts some DDL that PostgreSQL rejects, so H2-only migration tests cannot certify PG.
 */
class PostgresMigrationSmokeTest {
    private static EmbeddedPostgres postgres;
    private final Principal actor =
            new Principal("tenant", "agent", Set.of("AGENT"), Set.of("*"), Set.of("*"));

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void close() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void freshChainMigratesAndStoreQueriesRunOnPostgres() throws Exception {
        DataSource source = freshDatabase();
        PlatformTestDatabase.migrate(source);
        assertEquals("timestamp with time zone", columnType(source, "queue_due_at"));
        PlatformStore store = new PlatformStore(source);

        var first = store.create("tenant", "case", "case-b", caseData("B", null), "agent", "NEW");
        store.create(
                "tenant",
                "case",
                "case-a",
                caseData("A", "2026-03-01T00:00:00Z"),
                "agent",
                "NEW");
        store.create(
                "tenant",
                "material",
                "material-1",
                Map.of("case_id", "case-b", "status", "VERIFIED"),
                "agent",
                "NEW");
        store.transact(
                tx -> {
                    tx.putMaterialContent("tenant", "material-1", "hello".getBytes(), "agent");
                    return null;
                });
        assertEquals("hello", new String(store.materialContent("tenant", "material-1")));

        var updated =
                store.update(
                        "tenant",
                        "case",
                        "case-b",
                        first.version(),
                        caseData("B", "2026-02-01T00:00:00Z"),
                        "agent",
                        "EDIT");
        assertEquals(2, updated.version());
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        store.update(
                                                "tenant",
                                                "case",
                                                "case-b",
                                                1,
                                                Map.of(),
                                                "stale",
                                                "OVERWRITE"))
                        .status());
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        store.create(
                                                "tenant",
                                                "case",
                                                "case-a",
                                                Map.of(),
                                                "agent",
                                                "DUPLICATE"))
                        .status());

        var queue = store.caseQueue(actor, CaseFilters.none(), "", 1);
        assertEquals(List.of("case-b"), queue.items().stream().map(e -> e.id()).toList());
        var rest = store.caseQueue(actor, CaseFilters.none(), queue.cursor(), 1);
        assertEquals(List.of("case-a"), rest.items().stream().map(e -> e.id()).toList());
        assertEquals("", rest.cursor());

        var audits = store.auditPage(actor, "", 2);
        assertEquals(2, audits.items().size());
        assertFalse(audits.cursor().isEmpty());
        var older = store.auditPage(actor, audits.cursor(), 100);
        // Five committed events; the rejected CAS update and duplicate create rolled back.
        assertEquals(3, older.items().size());
        assertEquals("", older.cursor());

        assertEquals(
                1,
                store.scopedPage(actor, "material", "case-b", Set.of("VERIFIED"), null, "", 10)
                        .items()
                        .size());
        assertEquals(2, store.countScoped(actor, "case", "OPEN"));
        assertEquals(2, store.scopedSummaryRecent(actor, "case", 10).size());
        assertEquals(
                2, store.activePage("case", Set.of("OPEN"), "", 100).items().size());
        assertEquals(Set.of("tenant"), store.tenants());
        assertLockWaitsForCommit(store);
    }

    @Test
    void executionQueueClaimsAndCompletesOnPostgres() throws Exception {
        DataSource source = freshDatabase();
        PlatformTestDatabase.migrate(source);
        PlatformStore store = new PlatformStore(source);
        OrderSource orders = new OrderSource("demo", "", "", "");
        CaseService cases = new CaseService(store, orders);
        WorkflowEngine engine = new WorkflowEngine();
        engine.registerHandler(
                "compensation_suggestion",
                (input, execution) ->
                        Map.of(
                                "authoritative_amount",
                                5,
                                "decision_status",
                                "DETERMINED",
                                "suggested_method",
                                "CASH"));
        ExecutionService executions =
                new ExecutionService(
                        store,
                        cases,
                        orders,
                        new DemoExecutionRuntimeProvider(new RuleBaseService(), "demo"),
                        engine,
                        2,
                        50,
                        15,
                        new BigDecimal("10"));
        ExecutorService callers = Executors.newFixedThreadPool(5);
        try {
            var c =
                    cases.create(
                            actor, Map.of("order_id", "ORDER-1", "complaint_type", "OVERTIME"));
            Map<String, Object> request =
                    Map.of(
                            "case_id",
                            c.id(),
                            "scenario",
                            "compensation_suggestion",
                            "expected_case_version",
                            c.version());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> submitted = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                submitted.add(
                        callers.submit(
                                () -> {
                                    go.await();
                                    return executions.submit(actor, request, "same-key").id();
                                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<String> id : submitted) ids.add(id.get(20, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            String id = ids.iterator().next();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            Object status = null;
            while (System.nanoTime() < until) {
                status = executions.require(actor, id).data().get("status");
                if (Set.of("SUCCESS", "DEGRADED", "FAILED", "CANCELLED", "TIMED_OUT")
                        .contains(status)) break;
                Thread.sleep(25);
            }
            assertEquals("SUCCESS", status);
            assertEquals(1, store.list("tenant", "decision").size());
        } finally {
            callers.shutdownNow();
            executions.stop();
        }
    }

    @Test
    void legacyV2TextTimestampsConvertDuringUpgrade() throws Exception {
        DataSource source = freshDatabase();
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        try (Connection connection = source.getConnection();
                PreparedStatement record =
                        connection.prepareStatement(
                                "INSERT INTO platform_records(tenant_id,kind,record_id,"
                                        + "record_version,created_at,updated_at,payload,"
                                        + "scope_city,scope_station,queue_priority,queue_due_at)"
                                        + " VALUES ('tenant',?,?,1,?,?,?,'A','S',?,?)");
                PreparedStatement defaults =
                        connection.prepareStatement(
                                "INSERT INTO platform_records(tenant_id,kind,record_id,"
                                        + "record_version,created_at,updated_at,payload)"
                                        + " VALUES ('tenant','note','legacy-note',1,"
                                        + "'2026-01-03T00:00:00Z','2026-01-03T00:00:00Z','{}')");
                PreparedStatement audit =
                        connection.prepareStatement(
                                "INSERT INTO platform_audit_events(event_id,tenant_id,actor_id,"
                                        + "action,resource_id,created_at,payload) VALUES"
                                        + " ('event-1','tenant','agent','CASE_CREATED',"
                                        + "'legacy-case','2026-01-01T00:00:00.123456Z','{}')")) {
            record.setString(1, "case");
            record.setString(2, "legacy-case");
            record.setString(3, "2026-01-01T00:00:00.120500Z");
            record.setString(4, "2026-01-02T08:00:00+08:00");
            record.setString(
                    5,
                    JsonUtil.toJson(
                            Map.of(
                                    "status",
                                    "OPEN",
                                    "risk_level",
                                    "HIGH",
                                    "city_code",
                                    "A",
                                    "station_id",
                                    "S",
                                    "order_id",
                                    "O-1",
                                    "complaint_type",
                                    "OVERTIME",
                                    "due_at",
                                    "2026-01-05T00:00:00Z")));
            record.setInt(6, 0);
            record.setString(7, "2026-01-05T00:00:00Z");
            record.executeUpdate();
            record.setString(1, "material");
            record.setString(2, "legacy-material");
            record.setString(3, "2026-01-01T00:00:01Z");
            record.setString(4, "2026-01-01T00:00:01Z");
            record.setString(
                    5,
                    JsonUtil.toJson(
                            Map.of(
                                    "case_id",
                                    "legacy-case",
                                    "status",
                                    "VERIFIED",
                                    "content_base64",
                                    "aGVsbG8=")));
            record.setInt(6, 1);
            record.setString(7, "9999-01-01T00:00:00Z");
            record.executeUpdate();
            record.setString(1, "eval_result:run-1");
            record.setString(2, "legacy-result");
            record.setString(5, JsonUtil.toJson(Map.of("run_id", "run-1")));
            record.executeUpdate();
            defaults.executeUpdate();
            audit.executeUpdate();
        }

        PlatformTestDatabase.migrate(source);

        assertEquals("timestamp with time zone", columnType(source, "created_at"));
        assertEquals("timestamp with time zone", columnType(source, "queue_due_at"));
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT record_id,created_at,updated_at,queue_due_at,case_id,"
                                        + "record_status,risk_level FROM platform_records"
                                        + " ORDER BY record_id")) {
            assertTrue(rows.next());
            assertEquals("legacy-case", rows.getString("record_id"));
            assertEquals(Instant.parse("2026-01-01T00:00:00.120500Z"), instant(rows, "created_at"));
            assertEquals(Instant.parse("2026-01-02T00:00:00Z"), instant(rows, "updated_at"));
            assertEquals(Instant.parse("2026-01-05T00:00:00Z"), instant(rows, "queue_due_at"));
            assertEquals("OPEN", rows.getString("record_status"));
            assertEquals("HIGH", rows.getString("risk_level"));
            assertTrue(rows.next());
            assertEquals("legacy-material", rows.getString("record_id"));
            assertEquals("legacy-case", rows.getString("case_id"));
            assertTrue(rows.next());
            assertEquals("legacy-note", rows.getString("record_id"));
            assertEquals(Instant.parse("9999-01-01T00:00:00Z"), instant(rows, "queue_due_at"));
            assertTrue(rows.next());
            assertEquals("legacy-result", rows.getString("record_id"));
            assertFalse(rows.next());
        }
        PlatformStore store = new PlatformStore(source);
        assertEquals(
                "run-1",
                store.summaryPage("tenant", "eval_result:run-1", "", 10)
                        .get(0)
                        .data()
                        .get("run_id"));
        assertEquals("hello", new String(store.materialContent("tenant", "legacy-material")));
        var audit = store.auditPage(actor, "", 10).items().get(0);
        assertEquals("2026-01-01T00:00:00.123456Z", audit.get("created_at"));
        assertEquals(
                "legacy-case",
                store.caseQueue(actor, CaseFilters.none(), "", 10).items().get(0).id());
        // A post-upgrade insert that omits the queue column still gets the far-future default.
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO platform_records(tenant_id,kind,record_id,record_version,"
                            + "created_at,updated_at,payload) VALUES ('tenant','note','new-note',"
                            + "1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}')");
        }
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT queue_due_at FROM platform_records WHERE"
                                        + " record_id='new-note'")) {
            assertTrue(rows.next());
            assertEquals(Instant.parse("9999-01-01T00:00:00Z"), instant(rows, "queue_due_at"));
        }
    }

    @Test
    void pilotStuckOnOriginalV3RecoversWithCorrectedChainAndNoRepair() throws Exception {
        DataSource source = freshDatabase();
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        Flyway original =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/original-v3")
                        .ignoreMigrationPatterns("*:missing")
                        .load();
        var failure = assertThrows(RuntimeException.class, original::migrate);
        assertTrue(
                failure.getMessage()
                        .contains(
                                "default for column \"queue_due_at\" cannot be cast"
                                        + " automatically"),
                failure.getMessage());
        // PostgreSQL DDL is transactional: the failed V3 leaves V2 intact and records no row.
        assertEquals("character varying", columnType(source, "created_at"));
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM flyway_schema_history WHERE"
                                        + " version='3' OR NOT success")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        PlatformTestDatabase.migrate(source);
        assertEquals("timestamp with time zone", columnType(source, "queue_due_at"));
    }

    private void assertLockWaitsForCommit(PlatformStore store) throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        try {
            Future<Long> waiter =
                    store.transact(
                            tx -> {
                                var current = tx.requireForUpdate("tenant", "case", "case-a");
                                Future<Long> blocked =
                                        other.submit(
                                                () -> {
                                                    locked.await();
                                                    return store.transact(
                                                            second ->
                                                                    second.requireForUpdate(
                                                                                    "tenant",
                                                                                    "case",
                                                                                    "case-a")
                                                                            .version());
                                                });
                                locked.countDown();
                                try {
                                    Thread.sleep(300);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    fail(interrupted);
                                }
                                assertFalse(blocked.isDone(), "FOR UPDATE must block");
                                tx.update(
                                        "tenant",
                                        "case",
                                        "case-a",
                                        current.version(),
                                        caseData("A", "2026-03-01T00:00:00Z"),
                                        "worker",
                                        "CLAIMED");
                                return blocked;
                            });
            assertEquals(2L, waiter.get(10, TimeUnit.SECONDS));
        } finally {
            other.shutdownNow();
        }
    }

    private static Map<String, Object> caseData(String station, String dueAt) {
        return dueAt == null
                ? Map.of("status", "OPEN", "city_code", "C", "station_id", station)
                : Map.of(
                        "status", "OPEN", "city_code", "C", "station_id", station, "due_at", dueAt);
    }

    private static Instant instant(ResultSet rows, String column) throws Exception {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String columnType(DataSource source, String column) throws Exception {
        try (Connection connection = source.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT data_type FROM information_schema.columns WHERE"
                                        + " table_name='platform_records' AND column_name=?")) {
            query.setString(1, column);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static DataSource freshDatabase() throws Exception {
        String name = "harness_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return postgres.getDatabase("postgres", name);
    }
}
