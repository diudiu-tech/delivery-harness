package com.delivery.harness.platform.quality;

import com.delivery.harness.platform.store.PlatformStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists a completed evaluation run and its results exactly as the evaluator would. */
final class GateReports {
    static final String LABELLER = "independent-labeller";
    static final String EVALUATOR = "independent-evaluator";

    private GateReports() {}

    static Builder of(PlatformStore store, PlatformStore.Entity config) {
        return new Builder(store, config);
    }

    static final class Builder {
        private final PlatformStore store;
        private final PlatformStore.Entity config;
        private String split = "REAL_LABELLED";
        private String provenance = "SERVER_SNAPSHOT";
        private String datasetAuthor = LABELLER;
        private String initiator = EVALUATOR;
        private final Map<String, Integer> perScenario = new LinkedHashMap<>();

        private Builder(PlatformStore store, PlatformStore.Entity config) {
            this.store = store;
            this.config = config;
        }

        Builder split(String value) {
            split = value;
            return this;
        }

        Builder provenance(String value) {
            provenance = value;
            return this;
        }

        Builder datasetAuthor(String value) {
            datasetAuthor = value;
            return this;
        }

        Builder initiator(String value) {
            initiator = value;
            return this;
        }

        Builder samples(String scenario, int count) {
            perScenario.put(scenario, count);
            return this;
        }

        Builder everyScenario(int count) {
            RuntimeConfigService.SCENARIOS.forEach(scenario -> perScenario.put(scenario, count));
            return this;
        }

        PlatformStore.Entity store() {
            String runId = UUID.randomUUID().toString();
            List<Map<String, Object>> samples = new ArrayList<>();
            perScenario.forEach(
                    (scenario, count) -> {
                        for (int i = 0; i < count; i++) {
                            Map<String, Object> sample = new LinkedHashMap<>();
                            sample.put("sample_id", scenario + "-" + i);
                            sample.put("scenario", scenario);
                            sample.put("input", Map.of("order_id", scenario + "-" + i));
                            if (provenance != null) {
                                sample.put("evidence_provenance", provenance);
                            }
                            samples.add(sample);
                        }
                    });
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("split", split);
            dataset.put("samples", samples);
            if (datasetAuthor != null) {
                dataset.put("created_by", datasetAuthor);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "COMPLETED");
            data.put("hard_gate_passed", true);
            data.put("runtime_config_id", config.id());
            data.put("resolved_config", config.data());
            data.put("dataset_snapshot", dataset);
            data.put("git_sha", "a".repeat(40));
            if (initiator != null) {
                data.put("created_by", initiator);
            }
            PlatformStore.Entity run =
                    store.create(
                            config.tenantId(),
                            QualityEvalService.RUN,
                            runId,
                            data,
                            "SYSTEM",
                            "TEST");
            for (Map<String, Object> sample : samples) {
                store.create(
                        config.tenantId(),
                        QualityEvalService.RESULT + ":" + runId,
                        UUID.randomUUID().toString(),
                        Map.of(
                                "run_id",
                                runId,
                                "sample",
                                sample,
                                "scores",
                                Map.of("measurement_status", "MEASURED", "hard_gate_passed", true)),
                        "SYSTEM",
                        "TEST");
            }
            return run;
        }
    }
}
