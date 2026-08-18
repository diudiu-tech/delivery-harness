package com.delivery.harness.llm.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ModelRouter {

    @Value("${harness.llm.default-model:qwen2.5:7b}")
    private String defaultModel;

    private final Map<String, String> scenarioModelMap = new ConcurrentHashMap<>();

    public void registerModel(String scenario, String model) {
        scenarioModelMap.put(scenario, model);
        log.info("Model registered: scenario={}, model={}", scenario, model);
    }

    public String resolveModel(String scenario) {
        if (scenario == null) {
            return defaultModel;
        }
        return scenarioModelMap.getOrDefault(scenario, defaultModel);
    }

    public void removeModel(String scenario) {
        scenarioModelMap.remove(scenario);
    }

    public Map<String, String> listMappings() {
        return Collections.unmodifiableMap(new HashMap<>(scenarioModelMap));
    }
}
