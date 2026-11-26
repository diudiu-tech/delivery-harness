package com.delivery.harness.platform.quality;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.llm.gateway.LlmClient;
import com.delivery.harness.llm.gateway.LlmMessage;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.runtime.PersonalDataGuard;
import com.delivery.harness.runtime.RuntimeBinding;
import com.delivery.harness.runtime.RuntimeVisibility;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeConfigService {
    public static final String KIND = "runtime_config";
    public static final String ROUTE_KIND = "runtime_route";
    public static final int MAX_BUNDLE_BYTES = 512 * 1024;
    public static final List<String> SCENARIOS =
            List.of("abnormal_order_analysis", "compensation_suggestion");

    /** Sample evidence markers written only by the server; client-supplied evidence never gates. */
    public static final Set<String> SERVER_EVIDENCE =
            Set.of("SERVER_SNAPSHOT", "SERVER_SEED", "VERIFIED_FEEDBACK_SNAPSHOT");

    private static final Set<String> PILOT_GATE_SPLITS = Set.of("REAL_LABELLED", "FROZEN_BLIND");
    private static final Set<String> DEMO_GATE_SPLITS =
            Set.of("REAL_LABELLED", "FROZEN_BLIND", "SYNTHETIC_SMOKE", "BOUNDARY_REGRESSION");
    private final PlatformStore store;
    private final PolicyService policies;
    private final KnowledgeService knowledge;
    private final LlmClient llmClient;

    @Value("${harness.mode:demo}")
    private String mode = "demo";

    @Value("${harness.quality.publish-gate.min-measured-samples-per-scenario:20}")
    private int minMeasuredSamplesPerScenario = 20;

    @EventListener(ApplicationReadyEvent.class)
    @Order(-25)
    public void seedDemoDefaults() {
        if ("demo".equals(mode)) {
            ensureDefault("demo");
        }
    }

    public synchronized void ensureDefault(String tenant) {
        if ("pilot".equals(mode)) {
            return;
        }
        policies.ensureDefault(tenant);
        if (store.get(tenant, KIND, "runtime-default-v1").isPresent()) {
            return;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "configured-default");
        request.put("prompt", "你是配送运营辅助系统。只解释提供的已核验事实和确定性规则；不改写赔付金额，不编造证据。仅返回请求指定的 JSON 结构。");
        request.put("policy_version", "policy-default-v1");
        request.put("deterministic_only", true);
        request.put("schema_version", "2.0");
        Map<String, Object> data = executable(tenant, request);
        data.put("state", "PUBLISHED");
        data.put("created_by", "SYSTEM");
        requireBoundedBundle(data);
        store.create(tenant, KIND, "runtime-default-v1", data, "SYSTEM", "RUNTIME_SEED");
    }

    public PlatformStore.Entity create(String tenant, String actor, Map<String, Object> request) {
        Map<String, Object> data = executable(tenant, request);
        requireCurrentReferences(
                tenant,
                data.get("policy_version").toString(),
                QualityData.strings(data.get("knowledge_versions")));
        data.put("created_by", actor);
        requireBoundedBundle(data);
        return store.create(
                tenant,
                KIND,
                UUID.randomUUID().toString(),
                data,
                actor,
                "RUNTIME_CONFIG_CANDIDATE_CREATED");
    }

    private Map<String, Object> executable(String tenant, Map<String, Object> request) {
        String prompt = QualityData.required(request, "prompt", 32_000);
        PersonalDataGuard.requireClean("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", prompt);
        String model = QualityData.required(request, "model", 200);
        if ("configured-default".equals(model)
                && !Boolean.TRUE.equals(request.get("deterministic_only"))) {
            throw new IllegalArgumentException(
                    "Model-enabled configs must pin an explicit provider model identifier");
        }
        String policyId = QualityData.required(request, "policy_version", 100);
        if (!"configured-default"
                .equals(QualityData.text(request, "endpoint_ref", "configured-default"))) {
            throw new IllegalArgumentException(
                    "Unknown endpoint_ref; endpoints are administrator-managed server"
                            + " configuration");
        }
        int maxTokens = integer(request, "max_tokens", 2048, 1, 8192);
        int timeout = integer(request, "timeout_seconds", 30, 1, 120);
        int retries = integer(request, "max_retries", 0, 0, 2);
        BigDecimal temperature = new BigDecimal(QualityData.text(request, "temperature", "0"));
        if (temperature.signum() < 0 || temperature.compareTo(new BigDecimal("2")) > 0) {
            throw new IllegalArgumentException("temperature must be 0..2");
        }
        List<RuleInfo> rules = policies.resolve(tenant, policyId);
        rules.forEach(RuntimeBinding::requireRulePrivacySafe);
        List<String> knowledgeIds = QualityData.strings(request.get("knowledge_versions"));
        if (knowledgeIds.size() > 50) {
            throw new IllegalArgumentException("At most 50 knowledge versions per configuration");
        }
        PlatformStore.Entity policy = store.require(tenant, PolicyService.KIND, policyId);
        List<String> scenarios = QualityData.strings(request.get("scenarios"));
        if (scenarios.isEmpty()) {
            scenarios = SCENARIOS;
        } else if (!SCENARIOS.containsAll(scenarios)
                || Set.copyOf(scenarios).size() != scenarios.size()) {
            throw new IllegalArgumentException("scenarios must be distinct supported scenarios");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (request.containsKey("name")) {
            data.put("name", QualityData.required(request, "name", 256));
        }
        // The release gate requires measured coverage of every scenario the config may serve.
        data.put("scenarios", scenarios);
        data.put("model", model);
        data.put("endpoint_ref", "configured-default");
        data.put("prompt", prompt);
        data.put("prompt_hash", QualityData.hash(prompt));
        data.put("temperature", temperature);
        data.put("max_tokens", maxTokens);
        data.put("timeout_seconds", timeout);
        data.put("max_retries", retries);
        data.put("deterministic_only", Boolean.TRUE.equals(request.get("deterministic_only")));
        data.put("policy_version", policyId);
        data.put("policy_snapshot", rules.stream().map(JsonUtil::toMap).toList());
        data.put(
                "policy_scope",
                Map.of(
                        "city_codes",
                        policy.data().getOrDefault("city_codes", List.of()),
                        "station_ids",
                        policy.data().getOrDefault("station_ids", List.of()),
                        "effective_from",
                        policy.data().getOrDefault("effective_from", "2000-01-01T00:00:00Z"),
                        "effective_to",
                        policy.data().getOrDefault("effective_to", "9999-01-01T00:00:00Z")));
        data.put("knowledge_versions", knowledgeIds);
        data.put("knowledge_snapshot", knowledge.freeze(tenant, knowledgeIds));
        data.put("schema_version", "2.0");
        data.put("redaction_version", "allowlist-v1");
        data.put("scorer_version", "exact-fields-v1");
        data.put("baseline_version", "longest-stage-v1");
        Map<String, Object> pricing = QualityData.map(request.get("pricing"));
        if (!pricing.isEmpty()) {
            QualityData.required(pricing, "version", 100);
            if (!Set.of("CNY", "USD").contains(pricing.get("currency"))) {
                throw new IllegalArgumentException("Pricing requires CNY or USD currency");
            }
            for (String rate : List.of("input_per_million", "output_per_million")) {
                if (new BigDecimal(QualityData.required(pricing, rate, 30)).signum() < 0) {
                    throw new IllegalArgumentException("Pricing rates cannot be negative");
                }
            }
            data.put("pricing", pricing);
        }
        data.put("config_hash", QualityData.hash(data));
        data.put("state", "CANDIDATE");
        return data;
    }

    private static void requireBoundedBundle(Map<String, Object> data) {
        if (JsonUtil.toJson(data).getBytes(StandardCharsets.UTF_8).length > MAX_BUNDLE_BYTES) {
            throw new PlatformException(
                    422, "RUNTIME_BUNDLE_TOO_LARGE", "Runtime bundle exceeds 512 KiB");
        }
    }

    public static void authorizeRead(AuthContext.Principal actor, Map<String, Object> config) {
        if (!RuntimeVisibility.canRead(config, actor.cityCodes(), actor.stationIds())) {
            throw new PlatformException(404, "NOT_FOUND", "Runtime not found in authorized scope");
        }
    }

    /**
     * New configs and new publications must pin the current publication. SUPERSEDED
     * versions stay resolvable only for configs published before the supersession.
     */
    private void requireCurrentReferences(
            String tenant, String policyId, List<String> knowledgeIds) {
        if (!"PUBLISHED".equals(
                store.require(tenant, PolicyService.KIND, policyId).data().get("state"))) {
            throw new PlatformException(
                    409,
                    "RUNTIME_REFERENCE_NOT_CURRENT",
                    "Runtime configs must pin a currently published policy");
        }
        for (String knowledgeId : knowledgeIds) {
            if (!"PUBLISHED".equals(
                    store.require(tenant, KnowledgeService.KIND, knowledgeId)
                            .data()
                            .get("state"))) {
                throw new PlatformException(
                        409,
                        "RUNTIME_REFERENCE_NOT_CURRENT",
                        "Runtime configs must pin currently published knowledge versions");
            }
        }
    }

    public Map<String, Object> resolve(String tenant, String id) {
        Map<String, Object> result = resolveForEvaluation(tenant, id);
        if (!"PUBLISHED".equals(result.get("state"))) {
            throw new IllegalArgumentException(
                    "Runtime config must pass evaluation and be published before online use");
        }
        return result;
    }

    public Map<String, Object> resolveForEvaluation(String tenant, String id) {
        PlatformStore.Entity entity = store.require(tenant, KIND, id);
        Map<String, Object> result = QualityData.copy(entity.data());
        PersonalDataGuard.requireClean(
                "RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", QualityData.text(result, "prompt", ""));
        PolicyService.ruleMaps(result.get("policy_snapshot")).stream()
                .map(rule -> JsonUtil.fromMap(rule, RuleInfo.class))
                .forEach(RuntimeBinding::requireRulePrivacySafe);
        // Withdrawals stop new online runs, without replacing the frozen historical content.
        policies.resolve(tenant, result.get("policy_version").toString());
        knowledge.freeze(tenant, QualityData.strings(result.get("knowledge_versions")));
        result.put("config_id", id);
        return result;
    }

    public PlatformStore.Entity publish(
            String tenant,
            String id,
            String actor,
            long expectedVersion,
            PlatformStore.Entity report) {
        PlatformStore.Entity entity = store.require(tenant, KIND, id);
        if (!"CANDIDATE".equals(entity.data().get("state"))
                || !"COMPLETED".equals(report.data().get("status"))
                || !Boolean.TRUE.equals(report.data().get("hard_gate_passed"))
                || !id.equals(report.data().get("runtime_config_id"))
                || !entity.data()
                        .get("config_hash")
                        .equals(
                                QualityData.map(report.data().get("resolved_config"))
                                        .get("config_hash"))) {
            throw new IllegalArgumentException(
                    "Runtime publication requires a completed passing report for exactly this"
                            + " config");
        }
        if ("pilot".equals(mode)
                && !QualityData.text(report.data(), "git_sha", "").matches("[a-fA-F0-9]{40,64}")) {
            throw new IllegalArgumentException(
                    "Pilot publication requires a recorded build Git SHA");
        }
        if (actor.equals(entity.data().get("created_by"))) {
            throw new PlatformException(
                    403, "SEPARATION_OF_DUTIES", "Runtime publisher must differ from author");
        }
        requireCurrentReferences(
                tenant,
                entity.data().get("policy_version").toString(),
                QualityData.strings(entity.data().get("knowledge_versions")));
        requireGateEvidence(tenant, entity.data(), report);
        Map<String, Object> data = QualityData.copy(entity.data());
        data.put("state", "PUBLISHED");
        data.put("evaluation_run_id", report.id());
        data.put("published_by", actor);
        data.put("published_at", Instant.now().toString());
        return store.update(
                tenant, KIND, id, expectedVersion, data, actor, "RUNTIME_CONFIG_PUBLISHED");
    }

    /**
     * A passing report gates release only when its frozen dataset is an eligible split, every
     * sample's evidence was derived by the server, each declared scenario has enough measured
     * samples, and neither the labels nor the run came from the config author.
     */
    private void requireGateEvidence(
            String tenant, Map<String, Object> config, PlatformStore.Entity report) {
        Map<String, Object> dataset = QualityData.map(report.data().get("dataset_snapshot"));
        Set<String> splits = "pilot".equals(mode) ? PILOT_GATE_SPLITS : DEMO_GATE_SPLITS;
        if (!(dataset.get("split") instanceof String split) || !splits.contains(split)) {
            throw new PlatformException(
                    422,
                    "RUNTIME_GATE_SPLIT_INELIGIBLE",
                    "Runtime publication requires an evaluation on an eligible dataset split: "
                            + new TreeSet<>(splits));
        }
        List<Map<String, Object>> samples = PolicyService.ruleMaps(dataset.get("samples"));
        if (samples.isEmpty()
                || !samples.stream()
                        .allMatch(s -> serverEvidence(s.get("evidence_provenance")))) {
            throw new PlatformException(
                    422,
                    "RUNTIME_GATE_EVIDENCE_NOT_SERVER_DERIVED",
                    "Gating datasets must use server-derived evidence; client-supplied evidence"
                            + " is not eligible");
        }
        Object configAuthor = config.get("created_by");
        Object datasetAuthor = dataset.get("created_by");
        Object initiator = report.data().get("created_by");
        if (configAuthor == null || datasetAuthor == null || initiator == null) {
            throw new PlatformException(
                    422,
                    "RUNTIME_GATE_AUTHORSHIP_UNKNOWN",
                    "Gating config, dataset and evaluation must record their authors");
        }
        if (configAuthor.equals(datasetAuthor) || configAuthor.equals(initiator)) {
            throw new PlatformException(
                    403,
                    "SEPARATION_OF_DUTIES",
                    "Gating dataset author and evaluation initiator must differ from the config"
                            + " author");
        }
        Map<String, Integer> measured = new LinkedHashMap<>();
        List<PlatformStore.Entity> results =
                store.query(
                        tenant,
                        QualityEvalService.RESULT + ":" + report.id(),
                        null,
                        Set.of(),
                        null,
                        "",
                        100);
        for (PlatformStore.Entity result : results) {
            Map<String, Object> scores = QualityData.map(result.data().get("scores"));
            if ("MEASURED".equals(scores.get("measurement_status"))
                    && Boolean.TRUE.equals(scores.get("hard_gate_passed"))) {
                measured.merge(
                        QualityData.text(
                                QualityData.map(result.data().get("sample")), "scenario", ""),
                        1,
                        Integer::sum);
            }
        }
        int minimum = Math.max(1, minMeasuredSamplesPerScenario);
        for (String scenario : scenarios(config)) {
            if (measured.getOrDefault(scenario, 0) < minimum) {
                throw new PlatformException(
                        422,
                        "RUNTIME_GATE_INSUFFICIENT_SAMPLES",
                        "Scenario "
                                + scenario
                                + " needs at least "
                                + minimum
                                + " measured passing samples; found "
                                + measured.getOrDefault(scenario, 0));
            }
        }
    }

    /** Null-safe: legacy samples without a server marker are not gate-eligible. */
    public static boolean serverEvidence(Object provenance) {
        return provenance instanceof String marker && SERVER_EVIDENCE.contains(marker);
    }

    /** Legacy configs without a declaration may be routed to any scenario. */
    static List<String> scenarios(Map<String, Object> config) {
        List<String> declared = QualityData.strings(config.get("scenarios"));
        return declared.isEmpty() ? SCENARIOS : declared;
    }

    public PlatformStore.Entity withdraw(
            String tenant, String id, String actor, long expectedVersion, String reason) {
        return withdraw(tenant, KIND, id, actor, expectedVersion, reason, "RUNTIME_CONFIG");
    }

    public PlatformStore.Entity withdrawRoute(
            String tenant, String id, String actor, long expectedVersion, String reason) {
        return withdraw(tenant, ROUTE_KIND, id, actor, expectedVersion, reason, "RUNTIME_ROUTE");
    }

    /** Stops new online resolution; executions keep the configuration copy frozen at start. */
    private PlatformStore.Entity withdraw(
            String tenant,
            String kind,
            String id,
            String actor,
            long expectedVersion,
            String reason,
            String auditPrefix) {
        PlatformStore.Entity entity = store.require(tenant, kind, id);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Withdrawal requires a reason");
        }
        if (!"PUBLISHED".equals(entity.data().get("state"))) {
            throw new PlatformException(
                    409, "RUNTIME_NOT_PUBLISHED", "Only published runtime records can be withdrawn");
        }
        Map<String, Object> data = QualityData.copy(entity.data());
        data.put("state", "WITHDRAWN");
        data.put("withdrawn_by", actor);
        data.put("withdrawn_at", Instant.now().toString());
        data.put("withdrawal_reason", reason);
        return store.update(
                tenant, kind, id, expectedVersion, data, actor, auditPrefix + "_WITHDRAWN");
    }

    public RuntimeBinding bind(String tenant, String id, OrderInfo order, boolean verifiedDamage) {
        return RuntimeBinding.bind(order, null, resolve(tenant, id), verifiedDamage);
    }

    public PlatformStore.Entity createRoute(
            String tenant, String actor, Map<String, Object> request) {
        String scenario = QualityData.required(request, "scenario", 64);
        if (!SCENARIOS.contains(scenario)) {
            throw new IllegalArgumentException("Unsupported runtime route scenario");
        }
        String primary = QualityData.required(request, "primary_config_id", 100);
        String fallback = QualityData.required(request, "fallback_config_id", 100);
        for (String configId : List.of(primary, fallback)) {
            if (!scenarios(resolve(tenant, configId)).contains(scenario)) {
                throw new PlatformException(
                        422,
                        "RUNTIME_ROUTE_SCENARIO_NOT_GATED",
                        "Routed configs must have been gated on the route scenario");
            }
        }
        int percent = integer(request, "rollout_percent", 100, 0, 100);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scenario", scenario);
        data.put("primary_config_id", primary);
        data.put("fallback_config_id", fallback);
        data.put("rollout_percent", percent);
        data.put("city_codes", QualityData.strings(request.get("city_codes")));
        data.put("station_ids", QualityData.strings(request.get("station_ids")));
        data.put("state", "PUBLISHED");
        data.put("created_by", actor);
        return store.create(
                tenant,
                ROUTE_KIND,
                UUID.randomUUID().toString(),
                data,
                actor,
                "RUNTIME_ROUTE_PUBLISHED");
    }

    private static boolean preferred(PlatformStore.Entity route, PlatformStore.Entity selected) {
        int rank = Integer.compare(specificity(route.data()), specificity(selected.data()));
        if (rank != 0) {
            return rank > 0;
        }
        return Instant.parse(route.createdAt()).isAfter(Instant.parse(selected.createdAt()))
                || route.createdAt().equals(selected.createdAt())
                        && route.id().compareTo(selected.id()) > 0;
    }

    /** Station-scoped routes outrank city-scoped routes, which outrank tenant-wide routes. */
    static int specificity(Map<String, Object> route) {
        return (QualityData.strings(route.get("station_ids")).isEmpty() ? 0 : 2)
                + (QualityData.strings(route.get("city_codes")).isEmpty() ? 0 : 1);
    }

    /**
     * Case-stable rollout. The most specific published matching route wins, then the latest. A
     * withdrawn or unpublished config on the selected route fails closed rather than silently
     * falling back. The selected immutable map must be persisted before worker dispatch.
     */
    public Map<String, Object> route(
            String tenant, String scenario, String city, String station, String caseId) {
        PlatformStore.Entity selected = null;
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            List<PlatformStore.Entity> routes =
                    store.query(tenant, ROUTE_KIND, null, Set.of(), "PUBLISHED", cursor, 1000);
            for (PlatformStore.Entity route : routes) {
                if (scenario.equals(route.data().get("scenario"))
                        && PolicyService.applicable(route.data(), city, station, Instant.now())
                        && (selected == null || preferred(route, selected))) {
                    selected = route;
                }
            }
            if (routes.size() < 1000) {
                break;
            }
            if (page == 9) {
                throw new PlatformException(
                        503,
                        "RUNTIME_ROUTE_SCAN_LIMIT",
                        "Runtime route history exceeds the scan budget");
            }
            cursor = routes.get(routes.size() - 1).id();
        }
        if (selected == null) {
            return routable(tenant, "runtime-default-v1");
        }
        int bucket =
                Math.floorMod(
                        Integer.parseUnsignedInt(QualityData.hash(caseId).substring(0, 8), 16),
                        100);
        int percent = ((Number) selected.data().get("rollout_percent")).intValue();
        String id =
                selected.data()
                        .get(bucket < percent ? "primary_config_id" : "fallback_config_id")
                        .toString();
        Map<String, Object> resolved = routable(tenant, id);
        resolved.put("route_id", selected.id());
        resolved.put("rollout_bucket", bucket);
        return resolved;
    }

    private Map<String, Object> routable(String tenant, String id) {
        try {
            return resolve(tenant, id);
        } catch (IllegalArgumentException unavailable) {
            throw new PlatformException(
                    409,
                    "RUNTIME_ROUTE_CONFIG_UNAVAILABLE",
                    "The routed runtime config is withdrawn or unpublished; withdraw or replace"
                            + " the route");
        }
    }

    public Map<String, Object> probe(String tenant, String id) {
        Map<String, Object> config = resolveForEvaluation(tenant, id);
        if (Boolean.TRUE.equals(config.get("deterministic_only"))) {
            return Map.of(
                    "status",
                    "DETERMINISTIC_ONLY",
                    "compatible",
                    true,
                    "config_hash",
                    config.get("config_hash"));
        }
        long start = System.nanoTime();
        try (RuntimeBinding ignored = RuntimeBinding.bind(null, null, config, false)) {
            String result =
                    llmClient.chat(
                            config.get("model").toString(),
                            List.of(
                                    LlmMessage.builder()
                                            .role("user")
                                            .content(
                                                    "Return only {\"ok\":true}; this is a protocol"
                                                            + " compatibility probe without order"
                                                            + " data.")
                                            .build()),
                            List.of(),
                            0d,
                            50);
            Map<String, Object> parsed = JsonUtil.fromJson(result, Map.class);
            return Map.of(
                    "status",
                    "COMPLETED",
                    "compatible",
                    Boolean.TRUE.equals(parsed.get("ok")),
                    "duration_ms",
                    (System.nanoTime() - start) / 1_000_000,
                    "config_hash",
                    config.get("config_hash"));
        } catch (Exception e) {
            return Map.of(
                    "status",
                    "FAILED",
                    "compatible",
                    false,
                    "error_code",
                    "MODEL_ENDPOINT_NOT_ALLOWED".equals(e.getMessage())
                            ? "MODEL_ENDPOINT_NOT_ALLOWED"
                            : "MODEL_PROTOCOL_UNAVAILABLE");
        }
    }

    private static int integer(
            Map<String, Object> request, String key, int defaultValue, int min, int max) {
        int value =
                request.containsKey(key)
                        ? new BigDecimal(request.get(key).toString()).intValueExact()
                        : defaultValue;
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " outside allowed range");
        }
        return value;
    }
}
