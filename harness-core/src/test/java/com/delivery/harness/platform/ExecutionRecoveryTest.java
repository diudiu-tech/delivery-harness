package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.execution.DemoExecutionRuntimeProvider;
import com.delivery.harness.platform.execution.ExecutionService;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class ExecutionRecoveryTest {
    private ExecutionService service;
    private final Principal actor =
            new Principal("tenant", "agent", Set.of("AGENT"), Set.of("*"), Set.of("*"));

    @AfterEach
    void stop() {
        if (service != null) service.stop();
    }

    @Test
    void heartbeatInterleavedWithCompletionCannotDiscardASuccessfulWorkflow() throws Exception {
        DataSource database =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:finish-race-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        PlatformTestDatabase.migrate(database);
        HeartbeatInterleaving interleaving = new HeartbeatInterleaving(database);
        PlatformStore store = new PlatformStore(interleaving);
        OrderSource orders = new OrderSource("demo", "", "", "");
        CaseService cases = new CaseService(store, orders);
        WorkflowEngine engine = new WorkflowEngine();
        AtomicInteger calls = new AtomicInteger();
        engine.registerHandler(
                "abnormal_order_analysis",
                (input, execution) -> {
                    calls.incrementAndGet();
                    return Map.of("decision_status", "DETERMINED");
                });
        service = service(store, cases, orders, engine);
        interleaving.service = service;
        interleaving.store = store;
        try {
            var c =
                    cases.create(
                            actor, Map.of("order_id", "FINISH-RACE", "complaint_type", "OVERTIME"));
            var task =
                    service.submit(
                            actor,
                            Map.of("case_id", c.id(), "expected_case_version", c.version()),
                            "finish-race");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            PlatformStore.Entity completed;
            do {
                completed = store.require(actor.tenantId(), "execution", task.id());
                if (Set.of("SUCCESS", "DEGRADED", "FAILED", "TIMED_OUT", "CANCELLED")
                        .contains(completed.data().get("status"))) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertTrue(interleaving.injected.get(), "Heartbeat must race with the final write");
            assertTrue(interleaving.heartbeatCommitted.await(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals("SUCCESS", completed.data().get("status"));
            assertNull(completed.data().get("error_code"));
            assertEquals(1, store.caseRecords(actor.tenantId(), "decision", c.id(), 10).size());
            assertEquals(0L, service.queueStatus().get("background_failures"));
        } finally {
            interleaving.heartbeat.shutdownNow();
        }
    }

    @Test
    void recoveryFindsAnExpiredExecutionBeyondTheFirstThousandTenants() {
        PlatformStore store = PlatformTestDatabase.memory();
        store.transact(
                tx -> {
                    for (int index = 0; index < 1001; index++) {
                        String tenant = String.format("tenant-%04d", index);
                        tx.create(
                                tenant,
                                "case",
                                "case-1",
                                Map.of(
                                        "status",
                                        "ANALYZING",
                                        "city_code",
                                        "CITY",
                                        "station_id",
                                        "STATION",
                                        "current_execution_id",
                                        "run"),
                                "agent",
                                "CASE_CREATED");
                    }
                    tx.create(
                            "tenant-1000",
                            "execution",
                            "run",
                            Map.of(
                                    "status",
                                    "QUEUED",
                                    "deadline_at",
                                    Instant.now().minusSeconds(60).toString(),
                                    "case_id",
                                    "case-1"),
                            "agent",
                            "QUEUED");
                    return null;
                });
        service = service(store);
        ReflectionTestUtils.invokeMethod(service, "recoverSafely");
        assertEquals(
                "TIMED_OUT", store.require("tenant-1000", "execution", "run").data().get("status"));
    }

    @Test
    void recoveryAdvancesPastAFullPageOfUnexpiredLeases() {
        PlatformStore store = PlatformTestDatabase.memory();
        store.transact(
                tx -> {
                    tx.create(
                            "tenant",
                            "case",
                            "case-1",
                            Map.of("status", "ANALYZING", "current_execution_id", "run-100"),
                            "agent",
                            "CASE_CREATED");
                    for (int index = 0; index <= 100; index++) {
                        tx.create(
                                "tenant",
                                "execution",
                                String.format("run-%03d", index),
                                Map.of(
                                        "status",
                                        "RUNNING",
                                        "fence",
                                        "fence-" + index,
                                        "case_id",
                                        "case-1",
                                        "deadline_at",
                                        Instant.now()
                                                .plusSeconds(index == 100 ? -60 : 120)
                                                .toString(),
                                        "lease_until",
                                        Instant.now().plusSeconds(120).toString()),
                                "agent",
                                "RUNNING");
                    }
                    return null;
                });
        service = service(store);
        ReflectionTestUtils.invokeMethod(service, "recoverSafely");
        assertEquals(
                "RUNNING", store.require("tenant", "execution", "run-100").data().get("status"));
        ReflectionTestUtils.invokeMethod(service, "recoverSafely");
        assertEquals(
                "TIMED_OUT", store.require("tenant", "execution", "run-100").data().get("status"));
        assertEquals(
                "RUNNING", store.require("tenant", "execution", "run-000").data().get("status"));
        var page = store.activePage("execution", Set.of("RUNNING"), "", 1);
        assertEquals(1, page.items().size());
        assertFalse(page.cursor().isEmpty());
    }

    private ExecutionService service(PlatformStore store) {
        OrderSource orders = new OrderSource("demo", "", "", "");
        return service(store, new CaseService(store, orders), orders, new WorkflowEngine());
    }

    private ExecutionService service(
            PlatformStore store, CaseService cases, OrderSource orders, WorkflowEngine engine) {
        return new ExecutionService(
                store,
                cases,
                orders,
                new DemoExecutionRuntimeProvider(new RuleBaseService(), "demo"),
                engine,
                1,
                10,
                15,
                BigDecimal.TEN);
    }

    /** Force the old read/update race, while allowing a held row lock to serialize completion. */
    private static final class HeartbeatInterleaving extends DelegatingDataSource {
        private final AtomicBoolean finishLocked = new AtomicBoolean();
        private final AtomicBoolean injected = new AtomicBoolean();
        private final CountDownLatch heartbeatAttempted = new CountDownLatch(1);
        private final CountDownLatch heartbeatCommitted = new CountDownLatch(1);
        private final ExecutorService heartbeat =
                Executors.newSingleThreadExecutor(task -> new Thread(task, "test-heartbeat"));
        private ExecutionService service;
        private PlatformStore store;

        private HeartbeatInterleaving(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                Object value = invoke(connection, method, args);
                                if ("commit".equals(method.getName()) && heartbeatThread()) {
                                    heartbeatCommitted.countDown();
                                }
                                if ("prepareStatement".equals(method.getName())
                                        && args[0] instanceof String sql) {
                                    return statement((PreparedStatement) value, sql);
                                }
                                return value;
                            });
        }

        private PreparedStatement statement(PreparedStatement statement, String sql) {
            int kindAt = sql.indexOf("kind=?");
            int kindParameter =
                    kindAt < 0
                            ? -1
                            : 1
                                    + Math.toIntExact(
                                            sql.substring(0, kindAt)
                                                    .chars()
                                                    .filter(c -> c == '?')
                                                    .count());
            Map<Integer, Object> parameters = new HashMap<>();
            return (PreparedStatement)
                    Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (proxy, method, args) -> {
                                if (method.getName().startsWith("set")
                                        && args != null
                                        && args.length >= 2
                                        && args[0] instanceof Integer index)
                                    parameters.put(index, args[1]);
                                boolean execution =
                                        "execution".equals(parameters.get(kindParameter));
                                if (execution && "executeQuery".equals(method.getName())) {
                                    if (inMethod("finish") && sql.endsWith(" FOR UPDATE"))
                                        finishLocked.set(true);
                                    if (heartbeatThread() && inMethod("renew"))
                                        heartbeatAttempted.countDown();
                                }
                                if (execution
                                        && inMethod("finish")
                                        && "executeUpdate".equals(method.getName())
                                        && injected.compareAndSet(false, true)) {
                                    String tenant = parameters.get(kindParameter - 1).toString();
                                    String id = parameters.get(kindParameter + 1).toString();
                                    heartbeat.submit(
                                            () -> {
                                                var task = store.require(tenant, "execution", id);
                                                ReflectionTestUtils.invokeMethod(
                                                        service,
                                                        "renew",
                                                        tenant,
                                                        id,
                                                        task.data().get("fence").toString());
                                            });
                                    assertTrue(heartbeatAttempted.await(2, TimeUnit.SECONDS));
                                    if (!finishLocked.get()) {
                                        assertTrue(heartbeatCommitted.await(2, TimeUnit.SECONDS));
                                    }
                                }
                                return invoke(statement, method, args);
                            });
        }

        private static boolean inMethod(String method) {
            return Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(
                            frame ->
                                    ExecutionService.class.getName().equals(frame.getClassName())
                                            && method.equals(frame.getMethodName()));
        }

        private static boolean heartbeatThread() {
            return "test-heartbeat".equals(Thread.currentThread().getName());
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        }
    }
}
