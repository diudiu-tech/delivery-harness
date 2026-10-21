package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.platform.store.RecordScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class PlatformStoreTest {
    @Test
    void tenantIsolationAndCompareAndSwapAreEnforced() throws Exception {
        PlatformStore store = PlatformTestDatabase.memory();
        var first =
                store.create(
                        "tenant-a",
                        "case",
                        "same-id",
                        Map.of("status", "OPEN"),
                        "agent",
                        "CASE_CREATED");
        store.create(
                "tenant-b", "case", "same-id", Map.of("status", "SECRET"), "other", "CASE_CREATED");
        assertEquals("OPEN", store.require("tenant-a", "case", "same-id").data().get("status"));
        assertEquals("SECRET", store.require("tenant-b", "case", "same-id").data().get("status"));
        var updated =
                store.update(
                        "tenant-a",
                        "case",
                        "same-id",
                        first.version(),
                        Map.of("status", "APPROVED"),
                        "reviewer",
                        "REVIEW_APPROVED");
        assertEquals(2, updated.version());
        assertThrows(
                PlatformException.class,
                () ->
                        store.update(
                                "tenant-a", "case", "same-id", 1, Map.of(), "stale", "OVERWRITE"));
        assertEquals(2, store.audits("tenant-a").size());
        assertEquals(1, store.audits("tenant-b").size());
    }

    @Test
    void domainAndAuditRollbackTogether() {
        PlatformStore store = PlatformTestDatabase.memory();
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.transact(
                                tx -> {
                                    tx.create(
                                            "tenant",
                                            "review",
                                            "r",
                                            Map.of("status", "APPROVED"),
                                            "reviewer",
                                            "APPROVE");
                                    throw new IllegalStateException("failure after write");
                                }));
        assertTrue(store.get("tenant", "review", "r").isEmpty());
        assertTrue(store.audits("tenant").isEmpty());
    }

    @Test
    void concurrentClaimOnlyAllowsOneWinner() throws Exception {
        PlatformStore store = PlatformTestDatabase.memory();
        store.create("t", "review", "r", Map.of("status", "PENDING"), "system", "CREATE");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String actor = "reviewer-" + i;
                claims.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        store.update(
                                                "t",
                                                "review",
                                                "r",
                                                1,
                                                Map.of("status", "IN_REVIEW", "owner", actor),
                                                actor,
                                                "CLAIM");
                                        return true;
                                    } catch (PlatformException conflict) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> claim : claims) if (claim.get()) winners++;
            assertEquals(1, winners);
            assertEquals(2, store.audits("t").size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recordsSurviveDatabaseShutdownAndReopen(@TempDir Path directory) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("pilot");
        var source = PlatformTestDatabase.source(url);
        PlatformTestDatabase.migrate(source);
        new PlatformStore(source)
                .create(
                        "t",
                        "case",
                        "durable",
                        Map.of("status", "PENDING_REVIEW"),
                        "agent",
                        "CREATE");
        try (Connection connection = source.getConnection();
                Statement command = connection.createStatement()) {
            command.execute("SHUTDOWN");
        }
        PlatformStore restarted = new PlatformStore(PlatformTestDatabase.source(url));
        assertEquals(
                "PENDING_REVIEW", restarted.require("t", "case", "durable").data().get("status"));
        assertEquals(1, restarted.audits("t").size());
    }

    @Test
    void runtimePublicationCanChangeOnlyApprovedLifecycleMetadata() {
        var store = PlatformTestDatabase.memory();
        var before =
                store.create(
                        "t",
                        "runtime_config",
                        "config",
                        Map.of(
                                "state",
                                "CANDIDATE",
                                "model",
                                "model-a",
                                "config_hash",
                                "hash-a",
                                "temperature",
                                new BigDecimal("0.50")),
                        "author",
                        "CREATE");
        Map<String, Object> published = new LinkedHashMap<>(before.data());
        published.put("state", "PUBLISHED");
        published.put("evaluation_run_id", "passing-eval");
        assertEquals(
                2,
                store.update("t", "runtime_config", "config", 1, published, "publisher", "PUBLISH")
                        .version());
        Map<String, Object> changed = new LinkedHashMap<>(published);
        changed.put("model", "model-b");
        assertThrows(
                PlatformException.class,
                () ->
                        store.update(
                                "t", "runtime_config", "config", 2, changed, "publisher", "EDIT"));
        assertEquals(
                PlatformStore.hash(Map.of("n", new BigDecimal("35.50"))),
                PlatformStore.hash(Map.of("n", 35.5)));
    }

    @Test
    void immutableSnapshotsCannotBeRewritten() {
        var store = PlatformTestDatabase.memory();
        store.create("t", "decision", "d", Map.of("amount", 5), "actor", "CREATE");
        assertThrows(
                PlatformException.class,
                () -> store.update("t", "decision", "d", 1, Map.of("amount", 50), "actor", "EDIT"));
        assertEquals(5, store.require("t", "decision", "d").data().get("amount"));
    }

    @Test
    void caseQueueSortsRiskAndDeadlineGloballyBeforeScopeAndPageLimit() {
        var store = PlatformTestDatabase.memory();
        store.create(
                "t",
                "case",
                "a-normal",
                Map.of(
                        "risk_level",
                        "NORMAL",
                        "due_at",
                        "2026-09-01T00:00:00Z",
                        "city_code",
                        "C",
                        "station_id",
                        "S"),
                "actor",
                "CREATE");
        store.create(
                "t",
                "case",
                "z-high-later",
                Map.of(
                        "risk_level",
                        "HIGH",
                        "due_at",
                        "2026-09-04T00:00:00Z",
                        "city_code",
                        "C",
                        "station_id",
                        "S"),
                "actor",
                "CREATE");
        store.create(
                "t",
                "case",
                "y-high-first",
                Map.of(
                        "risk_level",
                        "HIGH",
                        "due_at",
                        "2026-09-03T00:00:00Z",
                        "city_code",
                        "C",
                        "station_id",
                        "S"),
                "actor",
                "CREATE");
        store.create(
                "t",
                "case",
                "b-denied",
                Map.of(
                        "risk_level",
                        "HIGH",
                        "due_at",
                        "2026-08-01T00:00:00Z",
                        "city_code",
                        "OTHER",
                        "station_id",
                        "S"),
                "actor",
                "CREATE");
        record Scope(String tenantId, Set<String> cityCodes, Set<String> stationIds)
                implements RecordScope {}
        var actor = new Scope("t", Set.of("C"), Set.of("S"));
        var first = store.caseQueue(actor, "", 1);
        assertEquals("y-high-first", first.items().get(0).id());
        assertFalse(first.cursor().isBlank());
        var second = store.caseQueue(actor, first.cursor(), 1);
        assertEquals("z-high-later", second.items().get(0).id());
        var third = store.caseQueue(actor, second.cursor(), 1);
        assertEquals("a-normal", third.items().get(0).id());
        assertEquals("", third.cursor());
        assertThrows(
                PlatformException.class, () -> store.caseQueue(actor, "not-a-valid-cursor", 1));
    }

    @Test
    void canonicalHashIgnoresMapInsertionOrderButPreservesValueChanges() {
        assertEquals(
                PlatformStore.hash(Map.of("a", 1, "b", 2)),
                PlatformStore.hash(new LinkedHashMap<>(Map.of("b", 2, "a", 1))));
        assertNotEquals(PlatformStore.hash(Map.of("a", 1)), PlatformStore.hash(Map.of("a", 2)));
    }

    @Test
    void cursorPaginationIsBounded() {
        var store = PlatformTestDatabase.memory();
        for (int i = 0; i < 4; i++)
            store.create("t", "case", "id-" + i, Map.of(), "actor", "CREATE");
        assertEquals(2, store.page("t", "case", "", 2).size());
        assertEquals("id-2", store.page("t", "case", "id-1", 2).get(0).id());
        assertThrows(PlatformException.class, () -> store.page("t", "case", "", 101));
    }
}
