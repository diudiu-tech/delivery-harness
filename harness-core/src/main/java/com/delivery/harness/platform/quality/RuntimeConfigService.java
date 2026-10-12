package com.delivery.harness.platform.quality;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.llm.gateway.LlmClient;
import com.delivery.harness.llm.gateway.LlmMessage;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.platform.store.PlatformException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeConfigService {
    public static final String KIND = "runtime_config";
    private final PlatformStore store;
    private final PolicyService policies;
    private final KnowledgeService knowledge;
    private final LlmClient llmClient;
    @Value("${harness.mode:demo}")
    private String mode = "demo";

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
        store.create(tenant, KIND, "runtime-default-v1", data, "SYSTEM", "RUNTIME_SEED");
    }

    public PlatformStore.Entity create(String tenant, String actor, Map<String, Object> request) {
        Map<String, Object> data = executable(tenant, request);
        data.put("created_by", actor);
        return store.create(tenant, KIND, UUID.randomUUID().toString(), data, actor, "RUNTIME_CONFIG_PUBLISHED");
    }

    private Map<String, Object> executable(String tenant, Map<String, Object> request) {
        String prompt = QualityData.required(request, "prompt", 32_000);
        PersonalDataGuard.requireClean("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", prompt);
        String model = QualityData.required(request, "model", 200);
        if ("configured-default".equals(model) && !Boolean.TRUE.equals(request.get("deterministic_only"))) {
            throw new IllegalArgumentException("Model-enabled configs must pin an explicit provider model identifier");
        }
        String policyId = QualityData.required(request, "policy_version", 100);
        if (!"configured-default".equals(QualityData.text(request, "endpoint_ref", "configured-default"))) {
            throw new IllegalArgumentException("Unknown endpoint_ref; endpoints are administrator-managed server configuration");
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
        Map<String, Object> data = new LinkedHashMap<>();
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
        data.put("policy_scope", Map.of("city_codes", policy.data().getOrDefault("city_codes", List.of()),
                "station_ids", policy.data().getOrDefault("station_ids", List.of()),
                "effective_from", policy.data().getOrDefault("effective_from", "2000-01-01T00:00:00Z"),
                "effective_to", policy.data().getOrDefault("effective_to", "9999-01-01T00:00:00Z")));
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

    public Map<String, Object> resolve(String tenant, String id) {
        Map<String, Object> result = resolveForEvaluation(tenant, id);
        if (!"PUBLISHED".equals(result.get("state"))) {
            throw new IllegalArgumentException("Runtime config must pass evaluation and be published before online use");
        }
        return result;
    }

    public Map<String, Object> resolveForEvaluation(String tenant, String id) {
        ensureDefault(tenant);
        PlatformStore.Entity entity = store.require(tenant, KIND, id);
        Map<String, Object> result = QualityData.copy(entity.data());
        PersonalDataGuard.requireClean("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", QualityData.text(result, "prompt", ""));
        PolicyService.ruleMaps(result.get("policy_snapshot")).stream()
                .map(rule -> JsonUtil.fromMap(rule, RuleInfo.class)).forEach(RuntimeBinding::requireRulePrivacySafe);
        // Withdrawals stop new online runs, without replacing the frozen historical content.
        policies.resolve(tenant, result.get("policy_version").toString());
        knowledge.freeze(tenant, QualityData.strings(result.get("knowledge_versions")));
        result.put("config_id", id);
        return result;
    }

    public PlatformStore.Entity publish(String tenant, String id, String actor, long expectedVersion, PlatformStore.Entity report) {
        PlatformStore.Entity entity = store.require(tenant, KIND, id);
        if (!"CANDIDATE".equals(entity.data().get("state"))
                || !"COMPLETED".equals(report.data().get("status"))
                || !Boolean.TRUE.equals(report.data().get("hard_gate_passed"))
                || !id.equals(report.data().get("runtime_config_id"))
                || !entity.data().get("config_hash").equals(QualityData.map(report.data().get("resolved_config")).get("config_hash"))) {
            throw new IllegalArgumentException("Runtime publication requires a completed passing report for exactly this config");
        }
        if ("pilot".equals(mode) && !QualityData.text(report.data(), "git_sha", "").matches("[a-fA-F0-9]{40,64}")) {
            throw new IllegalArgumentException("Pilot publication requires a recorded build Git SHA");
        }
        if (actor.equals(entity.data().get("created_by"))) {
            throw new PlatformException(403, "SEPARATION_OF_DUTIES", "Runtime publisher must differ from author");
        }
        Map<String, Object> data = QualityData.copy(entity.data());
        data.put("state", "PUBLISHED");
        data.put("evaluation_run_id", report.id());
        return store.update(tenant, KIND, id, expectedVersion, data, actor, "RUNTIME_CONFIG_PUBLISHED");
    }

    public RuntimeBinding bind(String tenant, String id, OrderInfo order, boolean verifiedDamage) {
        return RuntimeBinding.bind(order, null, resolve(tenant, id), verifiedDamage);
    }

    public PlatformStore.Entity createRoute(String tenant, String actor, Map<String, Object> request) {
        String scenario = QualityData.required(request, "scenario", 64);
        if (!Set.of("abnormal_order_analysis", "compensation_suggestion").contains(scenario)) {
            throw new IllegalArgumentException("Unsupported runtime route scenario");
        }
        String primary = QualityData.required(request, "primary_config_id", 100);
        String fallback = QualityData.required(request, "fallback_config_id", 100);
        resolve(tenant, primary);
        resolve(tenant, fallback);
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
        return store.create(tenant, "runtime_route", UUID.randomUUID().toString(), data, actor, "RUNTIME_ROUTE_PUBLISHED");
    }

    /** Case-stable rollout. The selected immutable map must be persisted before worker dispatch. */
    public Map<String, Object> route(String tenant, String scenario, String city, String station, String caseId) {
        List<PlatformStore.Entity> routes = store.list(tenant, "runtime_route").stream()
                .filter(r -> scenario.equals(r.data().get("scenario")))
                .filter(r -> PolicyService.applicable(r.data(), city, station, Instant.now())).toList();
        if (routes.isEmpty()) {
            return resolve(tenant, "runtime-default-v1");
        }
        PlatformStore.Entity selected = routes.get(routes.size() - 1);
        int bucket = Math.floorMod(Integer.parseUnsignedInt(QualityData.hash(caseId).substring(0, 8), 16), 100);
        int percent = ((Number) selected.data().get("rollout_percent")).intValue();
        String id = selected.data().get(bucket < percent ? "primary_config_id" : "fallback_config_id").toString();
        Map<String, Object> resolved = resolve(tenant, id);
        resolved.put("route_id", selected.id());
        resolved.put("rollout_bucket", bucket);
        return resolved;
    }

    public Map<String, Object> probe(String tenant, String id) {
        Map<String, Object> config = resolveForEvaluation(tenant, id);
        if (Boolean.TRUE.equals(config.get("deterministic_only"))) {
            return Map.of("status", "DETERMINISTIC_ONLY", "compatible", true, "config_hash", config.get("config_hash"));
        }
        long start = System.nanoTime();
        try (RuntimeBinding ignored = RuntimeBinding.bind(null, null, config, false)) {
            String result = llmClient.chat(config.get("model").toString(), List.of(LlmMessage.builder()
                    .role("user").content("Return only {\"ok\":true}; this is a protocol compatibility probe without order data.").build()),
                    List.of(), 0d, 50);
            Map<String, Object> parsed = JsonUtil.fromJson(result, Map.class);
            return Map.of("status", "COMPLETED", "compatible", Boolean.TRUE.equals(parsed.get("ok")),
                    "duration_ms", (System.nanoTime() - start) / 1_000_000, "config_hash", config.get("config_hash"));
        } catch (Exception e) {
            return Map.of("status", "FAILED", "compatible", false, "error_code",
                    "MODEL_ENDPOINT_NOT_ALLOWED".equals(e.getMessage()) ? "MODEL_ENDPOINT_NOT_ALLOWED" : "MODEL_PROTOCOL_UNAVAILABLE");
        }
    }

    private static int integer(Map<String, Object> request, String key, int defaultValue, int min, int max) {
        int value = request.containsKey(key) ? new BigDecimal(request.get(key).toString()).intValueExact() : defaultValue;
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " outside allowed range");
        }
        return value;
    }
}
