package com.delivery.harness.platform.execution;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.runtime.ExecutionRuntimeProvider;
import com.delivery.harness.runtime.RuntimeBinding;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable read-only workflow queue. CAS plus lease fencing prevents late/cancelled workers from
 * writing.
 */
@Service
public class ExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionService.class);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong backgroundFailures = new AtomicLong();
    private static final Set<String> TERMINAL =
            Set.of("SUCCESS", "DEGRADED", "FAILED", "CANCELLED", "TIMED_OUT");
    private final PlatformStore store;
    private final CaseService cases;
    private final OrderSource orders;
    private final ExecutionRuntimeProvider runtimes;
    private final WorkflowEngine workflows;
    private final ThreadPoolExecutor pool;
    private final int deadlineSeconds;
    private final BigDecimal approvalThreshold;
    private final ScheduledExecutorService recoveryTimer = scheduler("online-recovery", 1);
    private final ScheduledExecutorService heartbeatTimer = scheduler("online-heartbeat", 2);
    private final ScheduledExecutorService deadlineTimer = scheduler("online-deadline", 2);
    private final ConcurrentMap<String, Future<?>> running = new ConcurrentHashMap<>();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private String recoveryCursor = "";

    public ExecutionService(
            PlatformStore store,
            CaseService cases,
            OrderSource orders,
            ExecutionRuntimeProvider runtimes,
            WorkflowEngine workflows,
            @Value("${harness.execution.online-workers:2}") int workers,
            @Value("${harness.execution.online-queue:50}") int queue,
            @Value("${harness.execution.deadline-seconds:15}") int deadlineSeconds,
            @Value("${harness.review.approval-threshold:10}") BigDecimal approvalThreshold) {
        this.store = store;
        this.cases = cases;
        this.orders = orders;
        this.runtimes = runtimes;
        this.workflows = workflows;
        this.deadlineSeconds = Math.max(1, Math.min(120, deadlineSeconds));
        this.approvalThreshold = approvalThreshold;
        pool =
                new ThreadPoolExecutor(
                        Math.max(1, workers),
                        Math.max(1, workers),
                        0,
                        TimeUnit.MILLISECONDS,
                        new FairTaskQueue(Math.max(1, queue)),
                        r -> {
                            Thread t = new Thread(r, "online-workflow");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void start() {
        if (started.compareAndSet(false, true))
            recoveryTimer.scheduleWithFixedDelay(this::recoverSafely, 1, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        recoveryTimer.shutdownNow();
        heartbeatTimer.shutdownNow();
        deadlineTimer.shutdownNow();
        pool.shutdownNow();
    }

    public PlatformStore.Entity submit(Principal actor, Map<String, Object> request, String key) {
        Instant submittedAt = Instant.now();
        actor.requireRole("AGENT", "OPERATOR");
        if (key == null || key.isBlank() || key.length() > 200)
            throw PlatformException.invalid("Idempotency-Key is required (max 200 characters)");
        String caseId = CaseService.text(request, "case_id");
        PlatformStore.Entity c = cases.require(actor, caseId);
        String scenario =
                String.valueOf(request.getOrDefault("scenario", "abnormal_order_analysis"));
        if (!Set.of("abnormal_order_analysis", "compensation_suggestion").contains(scenario))
            throw PlatformException.invalid("Unsupported scenario");
        String hash = PlatformStore.hash(request);
        String idemId =
                PlatformStore.hash(
                        Map.of("actor", actor.userId(), "operation", "execution", "key", key));
        PlatformStore.Entity existing =
                store.get(actor.tenantId(), "idempotency", idemId).orElse(null);
        if (existing != null) return repeat(actor, existing, hash);
        if (pool.getQueue().remainingCapacity() == 0)
            throw new PlatformException(
                    429, "QUEUE_FULL", "Online workflow queue is full; retry later");
        long pending =
                store.query(
                                actor.tenantId(),
                                "execution",
                                null,
                                Set.of("QUEUED", "RUNNING"),
                                null,
                                "",
                                30)
                        .size();
        if (pending >= 30)
            throw new PlatformException(
                    429, "TENANT_QUOTA", "Tenant concurrent task quota is exhausted");
        Map<String, Object> config =
                request.get("runtime_config_id") == null
                        ? runtimes.route(
                                actor.tenantId(),
                                scenario,
                                CaseService.string(c.data(), "city_code"),
                                CaseService.string(c.data(), "station_id"),
                                caseId)
                        : runtimes.resolve(
                                actor.tenantId(), request.get("runtime_config_id").toString());
        OrderSource.Snapshot source = orders.query(actor, CaseService.text(c.data(), "order_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) config.get("policy_scope");
        LocalDateTime policyAt =
                source.order().getDeliveryTime() != null
                        ? source.order().getDeliveryTime()
                        : source.order().getCreateTime();
        Instant asOf =
                policyAt == null
                        ? Instant.now()
                        : policyAt.atZone(
                                        ZoneId.of(
                                                String.valueOf(
                                                        source.metadata()
                                                                .getOrDefault(
                                                                        "normalized_timezone",
                                                                        source.metadata()
                                                                                .getOrDefault(
                                                                                        "source_timezone",
                                                                                        "Asia/Shanghai")))))
                                .toInstant();
        if (!applicable(scope, source.order().getCityCode(), source.order().getStationId(), asOf))
            throw new PlatformException(
                    409,
                    "POLICY_OUT_OF_SCOPE",
                    "Selected policy is not applicable to this order time or scope");
        if (!Objects.equals(c.data().get("city_code"), source.order().getCityCode())
                || !Objects.equals(c.data().get("station_id"), source.order().getStationId()))
            throw new PlatformException(
                    409,
                    "ORDER_SCOPE_CHANGED",
                    "The live order moved outside the original case scope; create a new scoped"
                            + " case");
        PlatformStore.Entity result;
        try {
            result =
                    store.transact(
                            tx -> {
                                PlatformStore.Entity latest =
                                        tx.require(actor.tenantId(), "case", caseId);
                                CaseService.authorize(actor, latest);
                                CaseService.requireNoActivePayment(latest);
                                if (latest.version()
                                        != CaseService.number(request, "expected_case_version"))
                                    throw PlatformException.conflict(
                                            "Case changed before execution submission");
                                if ("ANALYZING".equals(latest.data().get("status")))
                                    throw PlatformException.conflict(
                                            "Case already has a running execution");
                                reserveQuota(tx, actor);
                                String id = UUID.randomUUID().toString();
                                String snapshotId = UUID.randomUUID().toString();
                                Map<String, Object> snapshot = CaseService.snapshot(source, caseId);
                                List<PlatformStore.Entity> materials =
                                        tx.caseRecords(actor.tenantId(), "material", caseId, 100);
                                snapshot.put(
                                        "material_versions",
                                        materials.stream()
                                                .map(
                                                        m ->
                                                                Map.of(
                                                                        "id",
                                                                        m.id(),
                                                                        "version",
                                                                        m.version(),
                                                                        "status",
                                                                        m.data().get("status")))
                                                .toList());
                                String requestedDamage =
                                        CaseService.string(request, "damage_level");
                                boolean verified =
                                        requestedDamage != null
                                                && materials.stream()
                                                        .anyMatch(
                                                                material ->
                                                                        verifiedDamage(
                                                                                tx,
                                                                                actor,
                                                                                material,
                                                                                requestedDamage));
                                tx.create(
                                        actor.tenantId(),
                                        "order_snapshot",
                                        snapshotId,
                                        snapshot,
                                        actor.userId(),
                                        "ORDER_SNAPSHOT_CAPTURED");
                                Map<String, Object> input = new LinkedHashMap<>();
                                input.put("order_id", latest.data().get("order_id"));
                                input.put("complaint_type", latest.data().get("complaint_type"));
                                if (request.get("damage_level") != null)
                                    input.put("damage_level", request.get("damage_level"));
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("case_id", caseId);
                                data.put("scenario", scenario);
                                data.put("input", input);
                                data.put("runtime_config", config);
                                data.put("runtime_config_id", config.get("config_id"));
                                data.put("runtime_config_hash", config.get("config_hash"));
                                data.put("evidence_snapshot_id", snapshotId);
                                data.put("status", "QUEUED");
                                data.put("submitted_by", actor.userId());
                                data.put("damage_evidence_verified", verified);
                                data.put("city_code", latest.data().get("city_code"));
                                data.put("station_id", latest.data().get("station_id"));
                                data.put("attempt", 0);
                                data.put(
                                        "deadline_at",
                                        submittedAt.plusSeconds(deadlineSeconds).toString());
                                data.put("deadline_budget_seconds", deadlineSeconds);
                                data.put("quota_reserved", true);
                                PlatformStore.Entity execution =
                                        tx.create(
                                                actor.tenantId(),
                                                "execution",
                                                id,
                                                data,
                                                actor.userId(),
                                                "EXECUTION_QUEUED");
                                tx.create(
                                        actor.tenantId(),
                                        "idempotency",
                                        idemId,
                                        Map.of(
                                                "request_hash",
                                                hash,
                                                "execution_id",
                                                id,
                                                "actor",
                                                actor.userId(),
                                                "expires_at",
                                                Instant.now().plusSeconds(86400).toString()),
                                        actor.userId(),
                                        "IDEMPOTENCY_RESERVED");
                                Map<String, Object> cd = new LinkedHashMap<>(latest.data());
                                cd.put("status", "ANALYZING");
                                cd.put("current_execution_id", id);
                                cd.put("evidence_snapshot_id", snapshotId);
                                cd.put("review_status", "PENDING");
                                cd.put("payment_status", "NOT_EXECUTED");
                                if (cd.get("review_id") != null) {
                                    PlatformStore.Entity review =
                                            tx.require(
                                                    actor.tenantId(),
                                                    "review",
                                                    cd.get("review_id").toString());
                                    Map<String, Object> rd = new LinkedHashMap<>(review.data());
                                    rd.put("status", "SUPERSEDED");
                                    tx.update(
                                            actor.tenantId(),
                                            "review",
                                            review.id(),
                                            review.version(),
                                            rd,
                                            actor.userId(),
                                            "REVIEW_SUPERSEDED");
                                }
                                tx.update(
                                        actor.tenantId(),
                                        "case",
                                        caseId,
                                        latest.version(),
                                        cd,
                                        actor.userId(),
                                        "CASE_ANALYSIS_QUEUED");
                                return execution;
                            });
        } catch (PlatformException e) {
            existing = store.get(actor.tenantId(), "idempotency", idemId).orElse(null);
            if (existing != null) return repeat(actor, existing, hash);
            throw e;
        }
        enqueue(result);
        return result;
    }

    private void reserveQuota(PlatformStore.Tx tx, Principal actor) {
        int capacity =
                Math.addExact(
                        pool.getMaximumPoolSize(),
                        Math.addExact(pool.getQueue().size(), pool.getQueue().remainingCapacity()));
        PlatformStore.Entity global =
                tx.getForUpdate("_SYSTEM", "execution_global_quota", "online").orElse(null);
        long total = global == null ? 0 : ((Number) global.data().get("active")).longValue();
        if (total >= capacity)
            throw new PlatformException(
                    429, "QUEUE_FULL", "Global online workflow quota is full; retry later");
        if (global == null)
            tx.create(
                    "_SYSTEM",
                    "execution_global_quota",
                    "online",
                    Map.of("active", Math.addExact(total, 1), "limit", capacity),
                    actor.userId(),
                    "GLOBAL_ONLINE_QUOTA_RESERVED");
        else
            tx.update(
                    "_SYSTEM",
                    "execution_global_quota",
                    "online",
                    global.version(),
                    Map.of("active", Math.addExact(total, 1), "limit", capacity),
                    actor.userId(),
                    "GLOBAL_ONLINE_QUOTA_RESERVED");
        PlatformStore.Entity quota =
                tx.getForUpdate(actor.tenantId(), "execution_quota", "online").orElse(null);
        long active =
                quota == null ? 0 : ((Number) quota.data().getOrDefault("active", 0)).longValue();
        if (active >= 30)
            throw new PlatformException(
                    429, "TENANT_QUOTA", "Tenant concurrent task quota is exhausted");
        Map<String, Object> data = Map.of("active", Math.addExact(active, 1), "limit", 30);
        if (quota == null)
            tx.create(
                    actor.tenantId(),
                    "execution_quota",
                    "online",
                    data,
                    actor.userId(),
                    "ONLINE_QUOTA_RESERVED");
        else
            tx.update(
                    actor.tenantId(),
                    "execution_quota",
                    "online",
                    quota.version(),
                    data,
                    actor.userId(),
                    "ONLINE_QUOTA_RESERVED");
    }

    private void releaseQuota(PlatformStore.Tx tx, PlatformStore.Entity execution, String actor) {
        if (!Boolean.TRUE.equals(execution.data().get("quota_reserved"))) return;
        PlatformStore.Entity global =
                tx.requireForUpdate("_SYSTEM", "execution_global_quota", "online");
        long total = ((Number) global.data().get("active")).longValue();
        tx.update(
                "_SYSTEM",
                "execution_global_quota",
                "online",
                global.version(),
                Map.of("active", Math.max(0, total - 1), "limit", global.data().get("limit")),
                actor,
                "GLOBAL_ONLINE_QUOTA_RELEASED");
        PlatformStore.Entity quota =
                tx.requireForUpdate(execution.tenantId(), "execution_quota", "online");
        long active = ((Number) quota.data().get("active")).longValue();
        tx.update(
                execution.tenantId(),
                "execution_quota",
                "online",
                quota.version(),
                Map.of("active", Math.max(0, active - 1), "limit", 30),
                actor,
                "ONLINE_QUOTA_RELEASED");
    }

    private PlatformStore.Entity repeat(Principal actor, PlatformStore.Entity idem, String hash) {
        if (!hash.equals(idem.data().get("request_hash")))
            throw new PlatformException(
                    409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used with different content");
        return require(actor, idem.data().get("execution_id").toString());
    }

    public PlatformStore.Entity require(Principal actor, String id) {
        PlatformStore.Entity execution = store.require(actor.tenantId(), "execution", id);
        cases.require(actor, CaseService.text(execution.data(), "case_id"));
        return execution;
    }

    public PlatformStore.Entity cancel(Principal actor, String id) {
        actor.requireRole("AGENT", "OPERATOR");
        require(actor, id);
        return terminal(actor.tenantId(), id, "CANCELLED", actor.userId());
    }

    private PlatformStore.Entity terminal(String tenant, String id, String status, String actor) {
        PlatformStore.Entity result =
                store.transact(
                        tx -> {
                            PlatformStore.Entity e = tx.requireForUpdate(tenant, "execution", id);
                            if (TERMINAL.contains(e.data().get("status"))) return e;
                            Map<String, Object> data = new LinkedHashMap<>(e.data());
                            data.put("status", status);
                            data.put("finished_at", Instant.now().toString());
                            PlatformStore.Entity updated =
                                    tx.update(
                                            tenant,
                                            "execution",
                                            id,
                                            e.version(),
                                            data,
                                            actor,
                                            "EXECUTION_" + status);
                            releaseQuota(tx, e, actor);
                            PlatformStore.Entity c =
                                    tx.requireForUpdate(
                                            tenant, "case", CaseService.text(data, "case_id"));
                            if (id.equals(c.data().get("current_execution_id"))
                                    && "ANALYZING".equals(c.data().get("status"))) {
                                Map<String, Object> cd = new LinkedHashMap<>(c.data());
                                cd.put("status", "OPEN");
                                tx.update(
                                        tenant,
                                        "case",
                                        c.id(),
                                        c.version(),
                                        cd,
                                        actor,
                                        "CASE_EXECUTION_STOPPED");
                            }
                            return updated;
                        });
        Future<?> future = running.get(tenant + ":" + id);
        if (future != null) future.cancel(true);
        pool.purge();
        return result;
    }

    private static boolean verifiedDamage(
            PlatformStore.Tx tx, Principal actor, PlatformStore.Entity material, String damage) {
        return "VERIFIED".equals(material.data().get("status"))
                && Boolean.TRUE.equals(material.data().get("content_checked"))
                && damage.equals(material.data().get("damage_level"))
                && tx.hasMaterialContent(actor.tenantId(), material.id());
    }

    private void enqueue(PlatformStore.Entity e) {
        String key = e.tenantId() + ":" + e.id();
        if (!scheduled.add(key)) return;
        try {
            OnlineTask future = new OnlineTask(e.tenantId(), e.id());
            running.put(key, future);
            pool.execute(future);
        } catch (RejectedExecutionException full) {
            scheduled.remove(key);
            running.remove(key);
        }
    }

    private final class OnlineTask extends FutureTask<Void> {
        private final String tenant;
        private final String key;

        private OnlineTask(String tenant, String id) {
            super(
                    () -> {
                        execute(tenant, id);
                        return null;
                    });
            this.tenant = tenant;
            this.key = tenant + ":" + id;
        }

        @Override
        protected void done() {
            try {
                get();
            } catch (CancellationException cancelled) {
                // Cancel/timeout is already represented by the durable terminal state.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                backgroundFailure("worker_interrupted", interrupted);
            } catch (ExecutionException failed) {
                backgroundFailure("worker", failed.getCause());
            } finally {
                scheduled.remove(key);
                running.remove(key, this);
            }
        }
    }

    /**
     * Alternate queued tenants when another tenant is waiting; preserve FIFO inside each tenant.
     */
    private static final class FairTaskQueue extends LinkedBlockingQueue<Runnable> {
        private final Object selection = new Object();
        private String lastTenant;

        private FairTaskQueue(int capacity) {
            super(capacity);
        }

        private void started(String tenant) {
            synchronized (selection) {
                lastTenant = tenant;
            }
        }

        private Runnable choose() {
            synchronized (selection) {
                Runnable first = super.peek();
                if (first == null) return null;
                Runnable selected = first;
                if (lastTenant != null) {
                    for (Runnable candidate : this) {
                        if (candidate instanceof ExecutionService.OnlineTask task
                                && !lastTenant.equals(task.tenant)) {
                            selected = candidate;
                            break;
                        }
                    }
                }
                if (!super.remove(selected)) return null;
                if (selected instanceof ExecutionService.OnlineTask task) lastTenant = task.tenant;
                return selected;
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            Runnable choice = choose();
            if (choice != null) return choice;
            Runnable first = super.take();
            synchronized (selection) {
                if (first instanceof ExecutionService.OnlineTask task) lastTenant = task.tenant;
            }
            return first;
        }

        @Override
        public Runnable poll() {
            return choose();
        }
    }

    private void execute(String tenant, String id) {
        String fence = UUID.randomUUID().toString();
        PlatformStore.Entity claimed;
        try {
            claimed =
                    store.transact(
                            tx -> {
                                PlatformStore.Entity e =
                                        tx.requireForUpdate(tenant, "execution", id);
                                if (!"QUEUED".equals(e.data().get("status"))) return null;
                                Map<String, Object> data = new LinkedHashMap<>(e.data());
                                data.put("status", "RUNNING");
                                data.put("fence", fence);
                                data.put("lease_until", Instant.now().plusSeconds(5).toString());
                                data.put("started_at", Instant.now().toString());
                                data.put(
                                        "attempt",
                                        Math.addExact(
                                                ((Number) data.getOrDefault("attempt", 0))
                                                        .intValue(),
                                                1));
                                return tx.update(
                                        tenant,
                                        "execution",
                                        id,
                                        e.version(),
                                        data,
                                        "WORKER",
                                        "EXECUTION_STARTED");
                            });
        } catch (PlatformException conflict) {
            backgroundFailure("claim", conflict);
            return;
        }
        if (claimed == null) return;
        ((FairTaskQueue) pool.getQueue()).started(tenant);
        if (Instant.now().isAfter(Instant.parse(claimed.data().get("deadline_at").toString()))) {
            terminal(tenant, id, "TIMED_OUT", "WORKER");
            return;
        }
        ScheduledFuture<?> heartbeat =
                heartbeatTimer.scheduleWithFixedDelay(
                        () -> renew(tenant, id, fence), 1, 1, TimeUnit.SECONDS);
        long budgetMillis =
                Math.max(
                        1,
                        Duration.between(
                                        Instant.now(),
                                        Instant.parse(claimed.data().get("deadline_at").toString()))
                                .toMillis());
        ScheduledFuture<?> budget =
                deadlineTimer.schedule(
                        () -> {
                            try {
                                terminal(tenant, id, "TIMED_OUT", "DEADLINE");
                            } catch (PlatformException failure) {
                                backgroundFailure("deadline", failure);
                            }
                        },
                        budgetMillis,
                        TimeUnit.MILLISECONDS);
        TraceUtil.setTraceId(id);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> frozenConfig =
                    (Map<String, Object>) claimed.data().get("runtime_config");
            Map<String, Object> config = new LinkedHashMap<>(frozenConfig);
            long remaining =
                    Math.max(
                            1,
                            Duration.between(
                                            Instant.now(),
                                            Instant.parse(
                                                    claimed.data().get("deadline_at").toString()))
                                    .toSeconds());
            config.put(
                    "timeout_seconds",
                    Math.min(
                            remaining,
                            Long.parseLong(config.getOrDefault("timeout_seconds", 30).toString())));
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) claimed.data().get("input");
            PlatformStore.Entity snapshot =
                    store.require(
                            tenant,
                            "order_snapshot",
                            claimed.data().get("evidence_snapshot_id").toString());
            OrderInfo order =
                    JsonUtil.fromMap(JsonUtil.toMap(snapshot.data().get("order")), OrderInfo.class);
            WorkflowExecution workflow;
            try (RuntimeBinding ignored =
                    RuntimeBinding.bind(
                            order,
                            null,
                            config,
                            Boolean.TRUE.equals(claimed.data().get("damage_evidence_verified")))) {
                workflow =
                        workflows.executeTrusted(claimed.data().get("scenario").toString(), input);
                config.put("model_call_metadata", RuntimeBinding.callMetadata());
            }
            finish(tenant, id, fence, workflow, config);
        } catch (Exception e) {
            fail(
                    tenant,
                    id,
                    fence,
                    e instanceof PlatformException pe ? pe.code() : "WORKFLOW_FAILED",
                    workflows.takeFailedExecution());
        } finally {
            heartbeat.cancel(false);
            budget.cancel(false);
            TraceUtil.clear();
        }
    }

    private void finish(
            String tenant,
            String id,
            String fence,
            WorkflowExecution workflow,
            Map<String, Object> config) {
        store.transact(
                tx -> {
                    PlatformStore.Entity e = tx.requireForUpdate(tenant, "execution", id);
                    if (!"RUNNING".equals(e.data().get("status"))
                            || !fence.equals(e.data().get("fence"))) return null;
                    if (Instant.now()
                            .isAfter(Instant.parse(e.data().get("deadline_at").toString())))
                        throw new PlatformException(
                                408,
                                "DEADLINE_EXCEEDED",
                                "Execution result arrived after deadline");
                    String caseId = CaseService.text(e.data(), "case_id");
                    Map<String, Object> output =
                            workflow.getOutput() == null
                                    ? new LinkedHashMap<>()
                                    : new LinkedHashMap<>(workflow.getOutput());
                    output.remove("model_raw_output");
                    output.remove("raw_model_output");
                    output.remove("llm_raw_output");
                    output.remove("model_output");
                    PlatformStore.Entity evidence =
                            tx.require(
                                    tenant,
                                    "order_snapshot",
                                    CaseService.text(e.data(), "evidence_snapshot_id"));
                    if ("STALE".equals(evidence.data().get("quality_status"))) {
                        output.put("decision_status", "INSUFFICIENT_EVIDENCE");
                        output.put("authoritative_amount", null);
                        output.put("amount_is_candidate", true);
                        output.put("needs_human_review", true);
                        output.put("source_quality_status", "STALE");
                        output.put("customer_message_status", "BLOCKED");
                    }
                    String decisionId = UUID.randomUUID().toString();
                    String reviewId = UUID.randomUUID().toString();
                    Map<String, Object> decision = new LinkedHashMap<>(output);
                    decision.put("case_id", caseId);
                    decision.put("execution_id", id);
                    decision.put("evidence_snapshot_id", e.data().get("evidence_snapshot_id"));
                    decision.put("policy_version", config.get("policy_version"));
                    decision.put("runtime_config_id", config.get("config_id"));
                    decision.put("runtime_config_hash", config.get("config_hash"));
                    decision.put("created_by", e.data().get("submitted_by"));
                    decision.putIfAbsent(
                            "decision_status",
                            "FAILED".equals(workflow.getStatus())
                                    ? "INSUFFICIENT_EVIDENCE"
                                    : "DETERMINED");
                    if ("compensation_suggestion".equals(e.data().get("scenario")))
                        decision.put("amount", output.get("authoritative_amount"));
                    decision.put(
                            "method", output.getOrDefault("suggested_method", "MANUAL_REVIEW"));
                    decision.put("currency", "CNY");
                    decision.put("payment_status", "NOT_EXECUTED");
                    decision.put("review_status", "PENDING");
                    tx.create(
                            tenant, "decision", decisionId, decision, "WORKER", "DECISION_CREATED");
                    Map<String, Object> ed = new LinkedHashMap<>(e.data());
                    ed.put("status", workflow.getStatus());
                    ed.put("finished_at", Instant.now().toString());
                    ed.put("duration_ms", workflow.getTotalDurationMs());
                    ed.put("steps", JsonUtil.toMap(workflow).get("steps"));
                    ed.put("decision_id", decisionId);
                    ed.put("output", output);
                    ed.put(
                            "model_call_metadata",
                            config.getOrDefault(
                                    "model_call_metadata", Map.of("cost_status", "UNKNOWN")));
                    tx.update(
                            tenant,
                            "execution",
                            id,
                            e.version(),
                            ed,
                            "WORKER",
                            "EXECUTION_FINISHED");
                    releaseQuota(tx, e, "WORKER");
                    // Keep the same execution -> global/tenant quota -> case lock order as
                    // stop/fail.
                    PlatformStore.Entity c = tx.requireForUpdate(tenant, "case", caseId);
                    if (id.equals(c.data().get("current_execution_id"))
                            && Objects.equals(
                                    c.data().get("evidence_snapshot_id"),
                                    e.data().get("evidence_snapshot_id"))) {
                        Map<String, Object> rd = new LinkedHashMap<>();
                        rd.put("case_id", c.id());
                        rd.put("decision_id", decisionId);
                        rd.put("decision_version", 1);
                        rd.put("evidence_snapshot_id", e.data().get("evidence_snapshot_id"));
                        rd.put("status", "PENDING");
                        rd.put("risk_level", output.getOrDefault("needs_human_review", true));
                        tx.create(tenant, "review", reviewId, rd, "WORKER", "REVIEW_CREATED");
                        Map<String, Object> cd = new LinkedHashMap<>(c.data());
                        cd.put("current_decision_id", decisionId);
                        cd.put("policy_version", config.get("policy_version"));
                        cd.put("review_id", reviewId);
                        cd.put(
                                "status",
                                "DETERMINED".equals(decision.get("decision_status"))
                                        ? "PENDING_REVIEW"
                                        : "NEEDS_EVIDENCE");
                        cd.put("review_status", "PENDING");
                        Object amount = decision.get("amount");
                        if (amount != null
                                && new BigDecimal(amount.toString()).compareTo(approvalThreshold)
                                        >= 0) cd.put("risk_level", "HIGH");
                        if (c.data().get("duplicate_case_ids") instanceof List<?> duplicates
                                && !duplicates.isEmpty()) cd.put("risk_level", "HIGH");
                        tx.update(
                                tenant,
                                "case",
                                c.id(),
                                c.version(),
                                cd,
                                "WORKER",
                                "CASE_DECISION_READY");
                    }
                    return null;
                });
    }

    private void fail(
            String tenant, String id, String fence, String code, WorkflowExecution partial) {
        try {
            store.transact(
                    tx -> {
                        PlatformStore.Entity e = tx.requireForUpdate(tenant, "execution", id);
                        if (!"RUNNING".equals(e.data().get("status"))
                                || !fence.equals(e.data().get("fence"))) return null;
                        Map<String, Object> d = new LinkedHashMap<>(e.data());
                        d.put("status", "DEADLINE_EXCEEDED".equals(code) ? "TIMED_OUT" : "FAILED");
                        d.put("error_code", code);
                        d.put("finished_at", Instant.now().toString());
                        if (partial != null) {
                            d.put("steps", JsonUtil.toMap(partial).get("steps"));
                            d.put("duration_ms", partial.getTotalDurationMs());
                            d.put("output", Redaction.safe(partial.getOutput()));
                        }
                        tx.update(
                                tenant,
                                "execution",
                                id,
                                e.version(),
                                d,
                                "WORKER",
                                "EXECUTION_FAILED");
                        releaseQuota(tx, e, "WORKER");
                        PlatformStore.Entity c =
                                tx.requireForUpdate(tenant, "case", CaseService.text(d, "case_id"));
                        if (id.equals(c.data().get("current_execution_id"))
                                && "ANALYZING".equals(c.data().get("status"))) {
                            Map<String, Object> cd = new LinkedHashMap<>(c.data());
                            cd.put("status", "OPEN");
                            tx.update(
                                    tenant,
                                    "case",
                                    c.id(),
                                    c.version(),
                                    cd,
                                    "WORKER",
                                    "CASE_EXECUTION_FAILED");
                        }
                        return null;
                    });
        } catch (PlatformException failure) {
            backgroundFailure("persist_failure", failure);
        }
    }

    private void renew(String tenant, String id, String fence) {
        try {
            store.transact(
                    tx -> {
                        PlatformStore.Entity e = tx.requireForUpdate(tenant, "execution", id);
                        if (!"RUNNING".equals(e.data().get("status"))
                                || !fence.equals(e.data().get("fence"))) return null;
                        if (Instant.now()
                                .isAfter(Instant.parse(e.data().get("deadline_at").toString()))) {
                            return null;
                        }
                        Map<String, Object> d = new LinkedHashMap<>(e.data());
                        d.put("lease_until", Instant.now().plusSeconds(5).toString());
                        tx.update(
                                tenant,
                                "execution",
                                id,
                                e.version(),
                                d,
                                "WORKER",
                                "EXECUTION_HEARTBEAT");
                        return null;
                    });
        } catch (PlatformException failure) {
            backgroundFailure("heartbeat", failure);
        } catch (RuntimeException failure) {
            backgroundFailure("heartbeat", failure);
        }
    }

    private void recoverSafely() {
        try {
            PlatformStore.Page page =
                    store.activePage("execution", Set.of("QUEUED", "RUNNING"), recoveryCursor, 100);
            recoveryCursor = page.cursor();
            for (PlatformStore.Entity candidate : page.items()) {
                try {
                    PlatformStore.Entity task = recoverLease(candidate.tenantId(), candidate.id());
                    if (task == null) continue;
                    if (Instant.now()
                            .isAfter(Instant.parse(task.data().get("deadline_at").toString()))) {
                        terminal(task.tenantId(), task.id(), "TIMED_OUT", "RECOVERY");
                    } else {
                        enqueue(task);
                    }
                } catch (RuntimeException failure) {
                    backgroundFailure("recover_record", failure);
                }
            }
        } catch (RuntimeException failure) {
            backgroundFailure("recover_scan", failure);
        }
    }

    private PlatformStore.Entity recoverLease(String tenant, String id) {
        return store.transact(
                tx -> {
                    PlatformStore.Entity current = tx.requireForUpdate(tenant, "execution", id);
                    if ("QUEUED".equals(current.data().get("status"))) return current;
                    if (!"RUNNING".equals(current.data().get("status"))) return null;
                    Instant now = Instant.now();
                    if (now.isAfter(Instant.parse(current.data().get("deadline_at").toString()))) {
                        return current;
                    }
                    if (!now.isAfter(Instant.parse(current.data().get("lease_until").toString())))
                        return null;
                    Map<String, Object> recovered = new LinkedHashMap<>(current.data());
                    recovered.put("status", "QUEUED");
                    recovered.remove("fence");
                    return tx.update(
                            tenant,
                            "execution",
                            id,
                            current.version(),
                            recovered,
                            "RECOVERY",
                            "EXECUTION_LEASE_RECOVERED");
                });
    }

    private void backgroundFailure(String operation, Throwable failure) {
        if (failure instanceof PlatformException conflict && conflict.status() == 409) return;
        backgroundFailures.incrementAndGet();
        String code =
                failure instanceof PlatformException platform
                        ? platform.code()
                        : failure.getClass().getSimpleName();
        LOG.error(
                "Execution background operation failed: operation={}, code={}, trace_id={}",
                operation,
                code,
                TraceUtil.getTraceId());
    }

    private static ScheduledExecutorService scheduler(String name, int threads) {
        return Executors.newScheduledThreadPool(
                threads,
                r -> {
                    Thread worker = new Thread(r, name);
                    worker.setDaemon(true);
                    return worker;
                });
    }

    private static boolean applicable(
            Map<String, Object> scope, String city, String station, Instant at) {
        if (scope == null) return false;
        Object cities = scope.get("city_codes");
        Object stations = scope.get("station_ids");
        boolean cityAllowed =
                cities instanceof List<?> list && (list.isEmpty() || list.contains(city));
        boolean stationAllowed =
                stations instanceof List<?> list && (list.isEmpty() || list.contains(station));
        return cityAllowed
                && stationAllowed
                && (!scope.containsKey("effective_from")
                        || !at.isBefore(Instant.parse(scope.get("effective_from").toString())))
                && (!scope.containsKey("effective_to")
                        || at.isBefore(Instant.parse(scope.get("effective_to").toString())));
    }

    public Map<String, Object> queueStatus() {
        return Map.of(
                "online_active",
                pool.getActiveCount(),
                "online_queued",
                pool.getQueue().size(),
                "online_queue_remaining",
                pool.getQueue().remainingCapacity(),
                "separate_eval_pool",
                true,
                "background_failures",
                backgroundFailures.get(),
                "independent_schedulers",
                true);
    }

    public static Map<String, Object> publicView(PlatformStore.Entity entity) {
        Map<String, Object> result = CaseService.view(entity);
        result.remove("runtime_config");
        result.remove("fence");
        result.remove("input");
        result.put("execution_id", entity.id());
        result.put("execution_status", entity.data().get("status"));
        result.put("status_url", "/api/v2/executions/" + entity.id());
        return Redaction.map(result);
    }
}
