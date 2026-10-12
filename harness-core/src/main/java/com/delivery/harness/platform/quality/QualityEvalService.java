package com.delivery.harness.platform.quality;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.eval.casemanager.EvalCaseManager;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.tool.order.OrderFixtures;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class QualityEvalService {
    public static final String DATASET = "eval_dataset";
    public static final String RUN = "eval_run";
    public static final String RESULT = "eval_result";
    private final PlatformStore store;
    private final RuntimeConfigService configs;
    private final WorkflowEngine workflows;
    private final OrderSource orders;
    private final EvalCaseManager seeds;
    private final String gitSha;
    @Value("${harness.mode:demo}")
    private String mode = "demo";
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> {
                Thread thread = new Thread(r, "quality-evaluation");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public QualityEvalService(PlatformStore store, RuntimeConfigService configs, WorkflowEngine workflows,
                              OrderSource orders, EvalCaseManager seeds, @Value("${harness.build.git-sha:UNKNOWN}") String gitSha) {
        this.store = store;
        this.configs = configs;
        this.workflows = workflows;
        this.orders = orders;
        this.seeds = seeds;
        this.gitSha = gitSha;
    }

    public synchronized void ensureDefaults(AuthContext.Principal actor) {
        configs.ensureDefault(actor.tenantId());
        if ("pilot".equals(mode)) {
            return;
        }
        if (store.get(actor.tenantId(), DATASET, "synthetic-smoke-v1").isPresent()) {
            return;
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (var evalCase : seeds.findAll()) {
            String orderId = evalCase.getInput().get("order_id").toString();
            OrderInfo order = OrderFixtures.build(orderId, LocalDateTime.of(2026, 1, 1, 12, 0));
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("sample_id", evalCase.getCaseId());
            sample.put("scenario", evalCase.getScenario());
            sample.put("input", evalCase.getInput());
            sample.put("evidence_order", JsonUtil.toMap(order));
            sample.put("evidence_hash", QualityData.hash(JsonUtil.toMap(order)));
            sample.put("data_mode", "SYNTHETIC");
            sample.put("expected_output", "compensation_suggestion".equals(evalCase.getScenario())
                    ? Map.of("amount", "EC-005".equals(evalCase.getCaseId()) ? "20.00" : "5.00", "currency", "CNY", "decision_status", "DETERMINED")
                    : Map.of("decision_status", "DETERMINED"));
            if ("compensation_suggestion".equals(evalCase.getScenario())) {
                sample.put("expected_rule_ids", evalCase.getExpectedRuleIds());
            } else {
                sample.put("expected_cause_codes", List.of("EC-004".equals(evalCase.getCaseId()) ? "NO_ANOMALY" : "UNKNOWN"));
            }
            samples.add(sample);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("samples", samples);
        data.put("split", "SYNTHETIC_SMOKE");
        data.put("dataset_hash", QualityData.hash(samples));
        data.put("label_note", "Synthetic contract regression; not an estimate of online responsibility accuracy");
        store.create(actor.tenantId(), DATASET, "synthetic-smoke-v1", data, "SYSTEM", "EVAL_DATASET_SEED");
    }

    public PlatformStore.Entity createDataset(AuthContext.Principal actor, Map<String, Object> request) {
        actor.requireRole("QUALITY", "ADMIN");
        String split = QualityData.required(request, "split", 64);
        if (!Set.of("SYNTHETIC_SMOKE", "BOUNDARY_REGRESSION", "REAL_LABELLED", "FROZEN_BLIND").contains(split)) {
            throw new IllegalArgumentException("Unsupported dataset split");
        }
        List<Map<String, Object>> samples = PolicyService.ruleMaps(request.get("samples"));
        if (samples.isEmpty() || samples.size() > 100) {
            throw new IllegalArgumentException("Dataset must contain 1..100 samples");
        }
        Set<String> evidenceIds = new java.util.HashSet<>();
        for (Map<String, Object> sample : samples) {
            String scenario = QualityData.required(sample, "scenario", 64);
            if (!Set.of("abnormal_order_analysis", "compensation_suggestion").contains(scenario)) {
                throw new IllegalArgumentException("Unsupported evaluation scenario");
            }
            Map<String, Object> input = QualityData.map(sample.get("input"));
            String orderId = QualityData.required(input, "order_id", 120);
            OrderInfo order;
            String mode;
            if (sample.containsKey("evidence_order")) {
                order = JsonUtil.fromMap(QualityData.map(sample.get("evidence_order")), OrderInfo.class);
                mode = QualityData.text(sample, "data_mode", "LABELLED_FROZEN");
                if (!orderId.equals(order.getOrderId())) {
                    throw new IllegalArgumentException("Input order and evidence order must match");
                }
                actor.authorizeScope(order.getCityCode(), order.getStationId());
            } else {
                OrderSource.Snapshot snapshot = orders.query(actor, orderId);
                order = snapshot.order();
                mode = String.valueOf(snapshot.metadata().get("source_type"));
            }
            sample.put("evidence_order", JsonUtil.toMap(order));
            sample.put("evidence_hash", QualityData.hash(JsonUtil.toMap(order)));
            sample.put("sample_id", QualityData.text(sample, "sample_id", UUID.randomUUID().toString()));
            sample.put("data_mode", mode);
            if (sample.get("source_feedback_id") != null) {
                PlatformStore.Entity feedback = store.require(actor.tenantId(), StructuredFeedbackService.KIND, sample.get("source_feedback_id").toString());
                if (!Set.of("CONFIRMED", "LINKED_FIX", "REGRESSION_VERIFIED").contains(feedback.data().get("status"))) {
                    throw new IllegalArgumentException("Feedback must be independently verified before use as a label source");
                }
                PlatformStore.Entity snapshot = store.require(actor.tenantId(), "order_snapshot", feedback.data().get("evidence_snapshot_id").toString());
                if (!QualityData.hash(snapshot.data().get("order")).equals(sample.get("evidence_hash"))) {
                    throw new IllegalArgumentException("Feedback sample must use the exact associated evidence snapshot");
                }
            }
            String dedup = orderId + ":" + sample.get("evidence_hash");
            if (!evidenceIds.add(dedup)) {
                throw new IllegalArgumentException("Duplicate order evidence in dataset");
            }
            if ("FROZEN_BLIND".equals(split)) {
                boolean leaked = store.list(actor.tenantId(), DATASET).stream()
                        .filter(d -> !"FROZEN_BLIND".equals(d.data().get("split")))
                        .flatMap(d -> PolicyService.ruleMaps(d.data().get("samples")).stream())
                        .anyMatch(s -> nearDuplicate(sample, s));
                if (leaked) {
                    throw new IllegalArgumentException("Blind dataset overlaps an existing tuning dataset");
                }
            } else {
                boolean blindLeak = store.list(actor.tenantId(), DATASET).stream()
                        .filter(d -> "FROZEN_BLIND".equals(d.data().get("split")))
                        .flatMap(d -> PolicyService.ruleMaps(d.data().get("samples")).stream())
                        .anyMatch(s -> nearDuplicate(sample, s));
                if (blindLeak) {
                    throw new IllegalArgumentException("Tuning dataset overlaps a frozen blind dataset");
                }
            }
        }
        return store.create(actor.tenantId(), DATASET, UUID.randomUUID().toString(),
                Map.of("samples", samples, "split", split, "dataset_hash", QualityData.hash(samples)), actor.userId(), "EVAL_DATASET_FROZEN");
    }

    public PlatformStore.Entity start(AuthContext.Principal actor, Map<String, Object> request) {
        actor.requireRole("QUALITY", "ADMIN");
        ensureDefaults(actor);
        String datasetId = QualityData.required(request, "dataset_version", 100);
        String configId = QualityData.required(request, "runtime_config_id", 100);
        PlatformStore.Entity dataset = store.require(actor.tenantId(), DATASET, datasetId);
        authorizeDataset(actor, dataset);
        Map<String, Object> config = configs.resolveForEvaluation(actor.tenantId(), configId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "QUEUED");
        data.put("dataset_version", datasetId);
        data.put("dataset_snapshot", dataset.data());
        data.put("runtime_config_id", configId);
        data.put("requested_config", configId);
        data.put("resolved_config", config);
        data.put("git_sha", gitSha);
        data.put("schema_version", "2.0");
        data.put("scorer_version", ExactScorer.VERSION);
        data.put("completed", 0);
        data.put("total", PolicyService.ruleMaps(dataset.data().get("samples")).size());
        data.put("trace_id", UUID.randomUUID().toString());
        data.put("created_by", actor.userId());
        PlatformStore.Entity run = store.create(actor.tenantId(), RUN, UUID.randomUUID().toString(), data, actor.userId(), "EVAL_RUN_QUEUED");
        try {
            Future<?> future = pool.submit(() -> execute(actor, run.id()));
            tasks.put(run.id(), future);
        } catch (RejectedExecutionException e) {
            terminal(actor, run.id(), "FAILED", "EVAL_QUEUE_FULL");
            throw new PlatformException(429, "EVAL_QUEUE_FULL", "Evaluation queue is full");
        }
        return run;
    }

    private void execute(AuthContext.Principal actor, String id) {
        try {
            PlatformStore.Entity run = store.require(actor.tenantId(), RUN, id);
            if (terminalStatus(run)) {
                return;
            }
            Map<String, Object> data = QualityData.copy(run.data());
            data.put("status", "RUNNING");
            data.put("started_at", Instant.now().toString());
            store.update(actor.tenantId(), RUN, id, run.version(), data, actor.userId(), "EVAL_STARTED");
            TraceUtil.setTraceId(data.get("trace_id").toString());
            Map<String, Object> config = QualityData.map(data.get("resolved_config"));
            List<Map<String, Object>> samples = PolicyService.ruleMaps(QualityData.map(data.get("dataset_snapshot")).get("samples"));
            for (Map<String, Object> sample : samples) {
                if (Thread.currentThread().isInterrupted() || terminalStatus(store.require(actor.tenantId(), RUN, id))) {
                    return;
                }
                OrderInfo order = JsonUtil.fromMap(QualityData.map(sample.get("evidence_order")), OrderInfo.class);
                actor.authorizeScope(order.getCityCode(), order.getStationId());
                Instant policyTime = order.getDeliveryTime() == null ? Instant.parse(run.createdAt())
                        : order.getDeliveryTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
                if (!PolicyService.applicable(QualityData.map(config.get("policy_scope")), order.getCityCode(), order.getStationId(), policyTime)) {
                    throw new IllegalArgumentException("Frozen policy is not applicable to evaluation evidence scope");
                }
                WorkflowExecution execution;
                Map<String, Object> call;
                try (RuntimeBinding ignored = RuntimeBinding.bind(order, null, config, Boolean.TRUE.equals(sample.get("damage_evidence_verified")))) {
                    execution = workflows.execute(sample.get("scenario").toString(), QualityData.map(sample.get("input")));
                    call = RuntimeBinding.callMetadata();
                }
                Map<String, Object> baselineConfig = QualityData.copy(config);
                baselineConfig.put("deterministic_only", true);
                WorkflowExecution baseline;
                try (RuntimeBinding ignored = RuntimeBinding.bind(order, null, baselineConfig, Boolean.TRUE.equals(sample.get("damage_evidence_verified")))) {
                    baseline = workflows.execute(sample.get("scenario").toString(), QualityData.map(sample.get("input")));
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("run_id", id);
                result.put("sample", sample);
                result.put("execution", JsonUtil.toMap(execution));
                result.put("scores", ExactScorer.score(sample, execution));
                result.put("baseline_scores", ExactScorer.score(sample, baseline));
                result.put("baseline_output", baseline.getOutput());
                result.put("legacy_baseline_cause", execution.getOutput() == null ? "UNKNOWN" : execution.getOutput().getOrDefault("baseline_cause", "UNKNOWN"));
                result.put("call_metadata", call);
                result.put("evidence_hash", sample.get("evidence_hash"));
                store.transact(tx -> {
                    PlatformStore.Entity current = tx.require(actor.tenantId(), RUN, id);
                    if (terminalStatus(current)) {
                        return null;
                    }
                    tx.create(actor.tenantId(), RESULT + ":" + id, UUID.randomUUID().toString(), result, actor.userId(), "EVAL_SAMPLE_COMPLETED");
                    Map<String, Object> next = QualityData.copy(current.data());
                    next.put("completed", ((Number) next.get("completed")).intValue() + 1);
                    tx.update(actor.tenantId(), RUN, id, current.version(), next, actor.userId(), "EVAL_PROGRESS");
                    return null;
                });
            }
            finish(actor, id);
        } catch (RuntimeException e) {
            terminal(actor, id, "FAILED", e instanceof PlatformException pe ? pe.code() : "EVALUATION_FAILED");
        } finally {
            tasks.remove(id);
            TraceUtil.clear();
        }
    }

    private void finish(AuthContext.Principal actor, String id) {
        List<PlatformStore.Entity> results = results(actor, id);
        PlatformStore.Entity run = store.require(actor.tenantId(), RUN, id);
        if (terminalStatus(run)) {
            return;
        }
        Map<String, Object> data = QualityData.copy(run.data());
        data.put("status", "COMPLETED");
        data.put("finished_at", Instant.now().toString());
        data.put("summary", summarize(results));
        data.put("hard_gate_passed", !results.isEmpty() && results.stream().allMatch(r -> Boolean.TRUE.equals(QualityData.map(r.data().get("scores")).get("hard_gate_passed"))));
        store.update(actor.tenantId(), RUN, id, run.version(), data, actor.userId(), "EVAL_COMPLETED");
    }

    public PlatformStore.Entity require(AuthContext.Principal actor, String id) {
        PlatformStore.Entity entity = store.require(actor.tenantId(), RUN, id);
        authorizeSamples(actor, QualityData.map(entity.data().get("dataset_snapshot")));
        return entity;
    }

    public List<PlatformStore.Entity> results(AuthContext.Principal actor, String id) {
        require(actor, id);
        return store.list(actor.tenantId(), RESULT + ":" + id);
    }

    public PlatformStore.Entity cancel(AuthContext.Principal actor, String id) {
        actor.requireRole("QUALITY", "ADMIN");
        require(actor, id);
        terminal(actor, id, "CANCELLED", "CANCELLED_BY_USER");
        Future<?> future = tasks.get(id);
        if (future != null) {
            future.cancel(true);
        }
        return require(actor, id);
    }

    public PlatformStore.Entity replay(AuthContext.Principal actor, String id) {
        actor.requireRole("QUALITY", "ADMIN");
        PlatformStore.Entity original = require(actor, id);
        if (!"COMPLETED".equals(original.data().get("status"))) {
            throw PlatformException.conflict("Only completed runs can be deterministically replayed");
        }
        Map<String, Object> data = QualityData.copy(original.data());
        data.put("replay_of", id);
        data.put("replay_mode", "STORED_OUTPUT_RESCORING");
        data.put("status", "RUNNING");
        String newId = UUID.randomUUID().toString();
        store.transact(tx -> {
            tx.create(actor.tenantId(), RUN, newId, data, actor.userId(), "EVAL_REPLAY_CREATED");
            for (PlatformStore.Entity result : results(actor, id)) {
                Map<String, Object> next = QualityData.copy(result.data());
                WorkflowExecution execution = JsonUtil.fromMap(QualityData.map(next.get("execution")), WorkflowExecution.class);
                Map<String, Object> rescored = ExactScorer.score(QualityData.map(next.get("sample")), execution);
                next.put("scores", rescored);
                next.put("run_id", newId);
                next.put("replay_equal", QualityData.hash(rescored).equals(QualityData.hash(result.data().get("scores"))));
                tx.create(actor.tenantId(), RESULT + ":" + newId, UUID.randomUUID().toString(), next, actor.userId(), "EVAL_REPLAY_RESULT");
            }
            return null;
        });
        finish(actor, newId);
        return require(actor, newId);
    }

    public PlatformStore.Entity retry(AuthContext.Principal actor, String id) {
        actor.requireRole("QUALITY", "ADMIN");
        PlatformStore.Entity original = require(actor, id);
        if (!terminalStatus(original)) {
            throw PlatformException.conflict("Retry requires an earlier terminal run");
        }
        Map<String, Object> next = QualityData.copy(original.data());
        next.put("status", "QUEUED");
        next.put("completed", 0);
        next.put("retry_of", id);
        next.put("trace_id", UUID.randomUUID().toString());
        next.remove("finished_at");
        next.remove("error_code");
        next.remove("summary");
        next.remove("hard_gate_passed");
        PlatformStore.Entity run = store.create(actor.tenantId(), RUN, UUID.randomUUID().toString(), next, actor.userId(), "EVAL_FROZEN_RETRY_QUEUED");
        try {
            tasks.put(run.id(), pool.submit(() -> execute(actor, run.id())));
        } catch (RejectedExecutionException e) {
            terminal(actor, run.id(), "FAILED", "EVAL_QUEUE_FULL");
            throw new PlatformException(429, "EVAL_QUEUE_FULL", "Evaluation queue is full");
        }
        return run;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (String tenant : store.tenants()) {
            for (PlatformStore.Entity run : store.list(tenant, RUN)) {
                if (Set.of("QUEUED", "RUNNING").contains(run.data().get("status"))) {
                    Map<String, Object> next = QualityData.copy(run.data());
                    next.put("status", "FAILED");
                    next.put("error_code", "PROCESS_RESTARTED");
                    next.put("recovery_action", "RETRY_FROZEN_SNAPSHOT_WITH_NEW_RUN_ID");
                    next.put("finished_at", Instant.now().toString());
                    store.update(tenant, RUN, run.id(), run.version(), next, "SYSTEM", "EVAL_RECOVERED_AS_INTERRUPTED");
                }
            }
        }
    }

    private static boolean nearDuplicate(Map<String, Object> first, Map<String, Object> second) {
        if (java.util.Objects.equals(QualityData.map(first.get("input")).get("order_id"),
                QualityData.map(second.get("input")).get("order_id"))) {
            return true;
        }
        Map<String, Object> a = QualityData.copy(QualityData.map(first.get("evidence_order")));
        Map<String, Object> b = QualityData.copy(QualityData.map(second.get("evidence_order")));
        for (String field : List.of("orderId", "userId", "merchantId", "riderId", "merchantName", "riderName",
                "pickupAddress", "deliveryAddress", "stationId", "stationName", "cityCode")) {
            a.remove(field);
            b.remove(field);
        }
        return QualityData.hash(a).equals(QualityData.hash(b));
    }

    public Map<String, Object> compare(AuthContext.Principal actor, String firstId, String secondId) {
        PlatformStore.Entity first = require(actor, firstId);
        PlatformStore.Entity second = require(actor, secondId);
        String a = String.valueOf(QualityData.map(first.data().get("dataset_snapshot")).get("dataset_hash"));
        String b = String.valueOf(QualityData.map(second.data().get("dataset_snapshot")).get("dataset_hash"));
        if (!a.equals(b)) {
            throw new IllegalArgumentException("Comparison requires the same frozen dataset evidence");
        }
        return Map.of("baseline", first, "candidate", second, "dataset_hash", a,
                "model_reinference_may_differ", true);
    }

    public static void authorizeDataset(AuthContext.Principal actor, PlatformStore.Entity dataset) {
        authorizeSamples(actor, dataset.data());
    }

    private static void authorizeSamples(AuthContext.Principal actor, Map<String, Object> dataset) {
        for (Map<String, Object> sample : PolicyService.ruleMaps(dataset.get("samples"))) {
            OrderInfo order = JsonUtil.fromMap(QualityData.map(sample.get("evidence_order")), OrderInfo.class);
            actor.authorizeScope(order.getCityCode(), order.getStationId());
        }
    }

    private static boolean terminalStatus(PlatformStore.Entity entity) {
        return Set.of("COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT").contains(entity.data().get("status"));
    }

    private void terminal(AuthContext.Principal actor, String id, String status, String error) {
        store.transact(tx -> {
            PlatformStore.Entity entity = tx.require(actor.tenantId(), RUN, id);
            if (!terminalStatus(entity)) {
                Map<String, Object> data = QualityData.copy(entity.data());
                data.put("status", status);
                data.put("error_code", error);
                data.put("finished_at", Instant.now().toString());
                tx.update(actor.tenantId(), RUN, id, entity.version(), data, actor.userId(), "EVAL_" + status);
            }
            return null;
        });
    }

    private static Map<String, Object> summarize(List<PlatformStore.Entity> results) {
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        int measured = 0;
        int passed = 0;
        Map<String, Map<String, Integer>> slices = new LinkedHashMap<>();
        for (PlatformStore.Entity result : results) {
            Map<String, Object> execution = QualityData.map(result.data().get("execution"));
            statusCounts.merge(QualityData.text(execution, "status", "FAILED"), 1, Integer::sum);
            Map<String, Object> scores = QualityData.map(result.data().get("scores"));
            if ("MEASURED".equals(scores.get("measurement_status"))) {
                measured++;
            }
            boolean pass = Boolean.TRUE.equals(scores.get("hard_gate_passed"));
            if (pass) {
                passed++;
            }
            Map<String, Object> sample = QualityData.map(result.data().get("sample"));
            String slice = QualityData.text(sample, "scenario", "UNKNOWN") + ":"
                    + QualityData.text(QualityData.map(sample.get("input")), "complaint_type", "ATTRIBUTION");
            Map<String, Integer> stats = slices.computeIfAbsent(slice, ignored -> new LinkedHashMap<>());
            stats.merge("total", 1, Integer::sum);
            stats.merge("passed", pass ? 1 : 0, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("execution_status_counts", statusCounts);
        summary.put("measured_samples", measured);
        summary.put("not_measured_samples", results.size() - measured);
        summary.put("total_samples", results.size());
        summary.put("label_coverage", results.isEmpty() ? 0 : (double) measured / results.size());
        summary.put("hard_gate_passed_samples", passed);
        summary.put("slices", slices);
        return summary;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
