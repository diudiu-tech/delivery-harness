package com.delivery.harness.platform.execution;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.runtime.ExecutionRuntimeProvider;
import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A deterministic demo configuration; pilot requires an explicit versioned provider. */
@Service
public class DemoExecutionRuntimeProvider implements ExecutionRuntimeProvider {
    private final String mode;
    private final Map<String, Object> frozen;

    public DemoExecutionRuntimeProvider(
            RuleBaseService rules, @Value("${harness.mode:demo}") String mode) {
        this.mode = mode;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("config_id", "runtime-default-v1");
        config.put("policy_version", "demo-seed-v1");
        List<RuleInfo> published = rules.findAll();
        if (published.isEmpty() && !"pilot".equals(mode)) {
            try (var input = new ClassPathResource("seed/rules.json").getInputStream()) {
                published =
                        JsonUtil.fromJson(
                                new String(input.readAllBytes(), StandardCharsets.UTF_8),
                                new TypeReference<List<RuleInfo>>() {});
            } catch (IOException failure) {
                throw new IllegalStateException("Demo rule seed is unavailable", failure);
            }
        }
        config.put("policy_snapshot", published.stream().map(JsonUtil::toMap).toList());
        config.put("knowledge_snapshot", List.of());
        config.put(
                "policy_scope",
                Map.of(
                        "city_codes",
                        List.of(),
                        "station_ids",
                        List.of(),
                        "effective_from",
                        "2000-01-01T00:00:00Z",
                        "effective_to",
                        "9999-01-01T00:00:00Z"));
        config.put("deterministic_only", true);
        config.put("schema_version", "2.0");
        config.put("timeout_seconds", 15);
        config.put("prompt", "Explain only frozen evidence and deterministic rules.");
        config.put("prompt_hash", PlatformStore.hash(config.get("prompt")));
        config.put("config_hash", PlatformStore.hash(config));
        frozen = Map.copyOf(config);
    }

    @Override
    public Map<String, Object> resolve(String tenant, String id) {
        if ("pilot".equals(mode)) {
            throw new PlatformException(
                    409,
                    "RUNTIME_PROVIDER_REQUIRED",
                    "Pilot requires a published runtime provider");
        }
        if (!"runtime-default-v1".equals(id)) {
            throw new PlatformException(404, "NOT_FOUND", "Demo runtime configuration not found");
        }
        return frozen;
    }

    @Override
    public Map<String, Object> route(
            String tenant, String scenario, String city, String station, String caseId) {
        return resolve(tenant, "runtime-default-v1");
    }
}
