package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RuntimeConfigurationTest {
    @Test
    void publicationRequiresExactPassingReportAndAnIndependentPublisher() {
        var store = PlatformTestDatabase.memory();
        var rules = new RuleBaseService();
        rules.save(
                RuleInfo.builder()
                        .ruleId("RULE-1")
                        .ruleName("rule")
                        .ruleType("compensation")
                        .conditions(Map.of("complaint_type", "OVERTIME", "min_overtime_minutes", 0))
                        .actions(
                                Map.of(
                                        "amount_type",
                                        "FIXED",
                                        "amount",
                                        "3.00",
                                        "method",
                                        "COUPON"))
                        .build());
        var policies = new PolicyService(store, rules);
        var knowledge = new KnowledgeService(store, policies);
        var configs =
                new RuntimeConfigService(
                        store,
                        policies,
                        knowledge,
                        (model, messages, tools, temperature, tokens) -> "{}");
        configs.ensureDefault("tenant");
        var candidate =
                configs.create(
                        "tenant",
                        "author",
                        Map.of(
                                "name",
                                "Named runtime",
                                "model",
                                "fixed-model",
                                "prompt",
                                "Explain only verified facts",
                                "policy_version",
                                "policy-default-v1",
                                "deterministic_only",
                                true));
        assertEquals("CANDIDATE", candidate.data().get("state"));
        assertEquals("Named runtime", candidate.data().get("name"));
        assertThrows(
                IllegalArgumentException.class, () -> configs.resolve("tenant", candidate.id()));
        PlatformStore.Entity wrong = report(candidate, "other-hash");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        configs.publish(
                                "tenant", candidate.id(), "publisher", candidate.version(), wrong));
        PlatformStore.Entity passing = GateReports.of(store, candidate).everyScenario(20).store();
        assertEquals(
                403,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        configs.publish(
                                                "tenant",
                                                candidate.id(),
                                                "author",
                                                candidate.version(),
                                                passing))
                        .status());
        var published =
                configs.publish(
                        "tenant", candidate.id(), "publisher", candidate.version(), passing);
        assertEquals("PUBLISHED", published.data().get("state"));
        assertEquals("publisher", published.data().get("published_by"));
        assertTrue(published.data().get("published_at") instanceof String);
        assertEquals(passing.id(), published.data().get("evaluation_run_id"));
        assertEquals(
                candidate.data().get("config_hash"),
                configs.resolve("tenant", candidate.id()).get("config_hash"));
        List<Object> actions =
                store.audits("tenant").stream()
                        .filter(audit -> candidate.id().equals(audit.get("target")))
                        .map(audit -> audit.get("action"))
                        .toList();
        assertEquals(
                List.of("RUNTIME_CONFIG_CANDIDATE_CREATED", "RUNTIME_CONFIG_PUBLISHED"), actions);
    }

    @Test
    void pilotGateRejectsSyntheticSplitsAndDemoStillRequiresServerEvidence() {
        Fixture f = new Fixture();
        ReflectionTestUtils.setField(f.configs, "mode", "pilot");
        for (String split : List.of("SYNTHETIC_SMOKE", "BOUNDARY_REGRESSION")) {
            var candidate = f.candidate(Map.of());
            var report = GateReports.of(f.store, candidate).split(split).everyScenario(20).store();
            assertGate(f, candidate, report, 422, "RUNTIME_GATE_SPLIT_INELIGIBLE");
        }
        var blind = f.candidate(Map.of());
        f.publish(
                blind, GateReports.of(f.store, blind).split("FROZEN_BLIND").everyScenario(20).store());
        ReflectionTestUtils.setField(f.configs, "mode", "demo");
        var smoke = f.candidate(Map.of());
        f.publish(
                smoke,
                GateReports.of(f.store, smoke).split("SYNTHETIC_SMOKE").everyScenario(20).store());
    }

    @Test
    void clientSuppliedOrUnmarkedEvidenceIsNeverGateEligible() {
        Fixture f = new Fixture();
        for (String provenance : new String[] {"CLIENT_SUPPLIED", "FORGED", null}) {
            var candidate = f.candidate(Map.of());
            var report =
                    GateReports.of(f.store, candidate)
                            .provenance(provenance)
                            .everyScenario(20)
                            .store();
            assertGate(f, candidate, report, 422, "RUNTIME_GATE_EVIDENCE_NOT_SERVER_DERIVED");
        }
    }

    @Test
    void gateRequiresConfiguredMeasuredSamplesForEveryDeclaredScenario() {
        Fixture f = new Fixture();
        var single = f.candidate(Map.of());
        assertGate(
                f,
                single,
                GateReports.of(f.store, single).everyScenario(1).store(),
                422,
                "RUNTIME_GATE_INSUFFICIENT_SAMPLES");
        var uncovered = f.candidate(Map.of());
        assertGate(
                f,
                uncovered,
                GateReports.of(f.store, uncovered).samples("compensation_suggestion", 20).store(),
                422,
                "RUNTIME_GATE_INSUFFICIENT_SAMPLES");
        ReflectionTestUtils.setField(f.configs, "minMeasuredSamplesPerScenario", 3);
        var small = f.candidate(Map.of());
        assertGate(
                f,
                small,
                GateReports.of(f.store, small).everyScenario(2).store(),
                422,
                "RUNTIME_GATE_INSUFFICIENT_SAMPLES");
        f.publish(small, GateReports.of(f.store, small).everyScenario(3).store());
        var scoped = f.candidate(Map.of("scenarios", List.of("compensation_suggestion")));
        assertEquals(List.of("compensation_suggestion"), scoped.data().get("scenarios"));
        f.publish(
                scoped,
                GateReports.of(f.store, scoped).samples("compensation_suggestion", 3).store());
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                f.configs.createRoute(
                                        "tenant",
                                        "publisher",
                                        Map.of(
                                                "scenario",
                                                "abnormal_order_analysis",
                                                "primary_config_id",
                                                scoped.id(),
                                                "fallback_config_id",
                                                scoped.id())));
        assertEquals("RUNTIME_ROUTE_SCENARIO_NOT_GATED", failure.code());
        assertThrows(
                IllegalArgumentException.class,
                () -> f.candidate(Map.of("scenarios", List.of("unknown_scenario"))));
    }

    @Test
    void datasetAuthorAndEvaluationInitiatorMustBeIndependentOfConfigAuthor() {
        Fixture f = new Fixture();
        var labelled = f.candidate(Map.of());
        assertGate(
                f,
                labelled,
                GateReports.of(f.store, labelled).datasetAuthor("author").everyScenario(20).store(),
                403,
                "SEPARATION_OF_DUTIES");
        var initiated = f.candidate(Map.of());
        assertGate(
                f,
                initiated,
                GateReports.of(f.store, initiated).initiator("author").everyScenario(20).store(),
                403,
                "SEPARATION_OF_DUTIES");
        var unknownDataset = f.candidate(Map.of());
        assertGate(
                f,
                unknownDataset,
                GateReports.of(f.store, unknownDataset).datasetAuthor(null).everyScenario(20).store(),
                422,
                "RUNTIME_GATE_AUTHORSHIP_UNKNOWN");
        var unknownRun = f.candidate(Map.of());
        assertGate(
                f,
                unknownRun,
                GateReports.of(f.store, unknownRun).initiator(null).everyScenario(20).store(),
                422,
                "RUNTIME_GATE_AUTHORSHIP_UNKNOWN");
    }

    @Test
    void publishedConfigsAndRoutesWithdrawAndStopNewResolutionOnly() {
        Fixture f = new Fixture();
        var candidate = f.candidate(Map.of());
        var published =
                f.publish(candidate, GateReports.of(f.store, candidate).everyScenario(20).store());
        Map<String, Object> frozen = f.configs.resolve("tenant", candidate.id());
        var route =
                f.configs.createRoute(
                        "tenant",
                        "publisher",
                        Map.of(
                                "scenario",
                                "compensation_suggestion",
                                "primary_config_id",
                                candidate.id(),
                                "fallback_config_id",
                                candidate.id(),
                                "city_codes",
                                List.of("010")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        f.configs.withdraw(
                                "tenant", candidate.id(), "publisher", published.version(), " "));
        var withdrawn =
                f.configs.withdraw(
                        "tenant", candidate.id(), "publisher", published.version(), "regression");
        assertEquals("WITHDRAWN", withdrawn.data().get("state"));
        assertEquals("publisher", withdrawn.data().get("withdrawn_by"));
        assertEquals("regression", withdrawn.data().get("withdrawal_reason"));
        assertEquals(published.data().get("published_by"), withdrawn.data().get("published_by"));
        assertThrows(
                IllegalArgumentException.class, () -> f.configs.resolve("tenant", candidate.id()));
        // Frozen copies taken before withdrawal remain the executable record of earlier runs.
        assertEquals(candidate.data().get("config_hash"), frozen.get("config_hash"));
        assertEquals(
                "RUNTIME_ROUTE_CONFIG_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        f.configs.route(
                                                "tenant",
                                                "compensation_suggestion",
                                                "010",
                                                "S001",
                                                "case"))
                        .code());
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        f.configs.withdraw(
                                                "tenant",
                                                candidate.id(),
                                                "publisher",
                                                withdrawn.version(),
                                                "again"))
                        .status());
        Map<String, Object> republish = QualityData.copy(withdrawn.data());
        republish.put("state", "PUBLISHED");
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        f.store.update(
                                                "tenant",
                                                RuntimeConfigService.KIND,
                                                candidate.id(),
                                                withdrawn.version(),
                                                republish,
                                                "publisher",
                                                "TEST"))
                        .status());
        var routeWithdrawn =
                f.configs.withdrawRoute(
                        "tenant", route.id(), "publisher", route.version(), "config withdrawn");
        assertEquals("WITHDRAWN", routeWithdrawn.data().get("state"));
        assertEquals(
                "runtime-default-v1",
                f.configs
                        .route("tenant", "compensation_suggestion", "010", "S001", "case")
                        .get("config_id"));
        Map<String, Object> retarget = QualityData.copy(routeWithdrawn.data());
        retarget.put("state", "PUBLISHED");
        assertThrows(
                PlatformException.class,
                () ->
                        f.store.update(
                                "tenant",
                                RuntimeConfigService.ROUTE_KIND,
                                route.id(),
                                routeWithdrawn.version(),
                                retarget,
                                "publisher",
                                "TEST"));
        List<Object> actions =
                f.store.audits("tenant").stream()
                        .map(audit -> audit.get("action"))
                        .filter(action -> action.toString().endsWith("_WITHDRAWN"))
                        .toList();
        assertEquals(List.of("RUNTIME_CONFIG_WITHDRAWN", "RUNTIME_ROUTE_WITHDRAWN"), actions);
    }

    @Test
    void mostSpecificRouteWinsOverLaterBroaderRoutes() throws InterruptedException {
        Fixture f = new Fixture();
        var cityConfig = f.candidate(Map.of());
        f.publish(cityConfig, GateReports.of(f.store, cityConfig).everyScenario(20).store());
        var stationConfig = f.candidate(Map.of());
        f.publish(stationConfig, GateReports.of(f.store, stationConfig).everyScenario(20).store());
        var station = f.route(stationConfig.id(), List.of("010"), List.of("S001"));
        var city = f.route(cityConfig.id(), List.of("010"), List.of());
        var global = f.route("runtime-default-v1", List.of(), List.of());
        assertEquals(station.id(), f.routeId("010", "S001"));
        assertEquals(city.id(), f.routeId("010", "S002"));
        assertEquals(global.id(), f.routeId("020", "S009"));
        Thread.sleep(5); // Distinct creation instants make recency, not the id tiebreak, decide.
        var newerCity = f.route(stationConfig.id(), List.of("010"), List.of());
        assertEquals(newerCity.id(), f.routeId("010", "S002"));
        assertEquals(station.id(), f.routeId("010", "S001"));
        f.configs.withdrawRoute("tenant", station.id(), "publisher", station.version(), "done");
        assertEquals(newerCity.id(), f.routeId("010", "S001"));
    }

    private static void assertGate(
            Fixture f,
            PlatformStore.Entity candidate,
            PlatformStore.Entity report,
            int status,
            String code) {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                f.configs.publish(
                                        "tenant",
                                        candidate.id(),
                                        "publisher",
                                        candidate.version(),
                                        report));
        assertEquals(status, failure.status(), failure.getMessage());
        assertEquals(code, failure.code());
        assertEquals(
                "CANDIDATE",
                f.store.require("tenant", RuntimeConfigService.KIND, candidate.id())
                        .data()
                        .get("state"));
    }

    private static final class Fixture {
        final PlatformStore store = PlatformTestDatabase.memory();
        final RuntimeConfigService configs;

        Fixture() {
            var policies = new PolicyService(store, new RuleBaseService());
            configs =
                    new RuntimeConfigService(
                            store,
                            policies,
                            new KnowledgeService(store, policies),
                            (model, messages, tools, temperature, tokens) -> "{}");
            configs.ensureDefault("tenant");
        }

        PlatformStore.Entity candidate(Map<String, Object> extra) {
            Map<String, Object> request =
                    new LinkedHashMap<>(
                            Map.of(
                                    "model",
                                    "fixed-model",
                                    "prompt",
                                    "Explain only verified facts " + System.nanoTime(),
                                    "policy_version",
                                    "policy-default-v1",
                                    "deterministic_only",
                                    true));
            request.putAll(extra);
            return configs.create("tenant", "author", request);
        }

        PlatformStore.Entity publish(PlatformStore.Entity candidate, PlatformStore.Entity report) {
            return configs.publish(
                    "tenant", candidate.id(), "publisher", candidate.version(), report);
        }

        PlatformStore.Entity route(String configId, List<String> cities, List<String> stations) {
            return configs.createRoute(
                    "tenant",
                    "publisher",
                    Map.of(
                            "scenario",
                            "compensation_suggestion",
                            "primary_config_id",
                            configId,
                            "fallback_config_id",
                            configId,
                            "city_codes",
                            cities,
                            "station_ids",
                            stations));
        }

        Object routeId(String city, String station) {
            return configs.route("tenant", "compensation_suggestion", city, station, "case")
                    .get("route_id");
        }
    }

    @Test
    void frozenKnowledgeBundleIsBoundedBeforeCandidatePersistence() {
        var store = PlatformTestDatabase.memory();
        var policies = new PolicyService(store, new RuleBaseService());
        var knowledge = new KnowledgeService(store, policies);
        var configs =
                new RuntimeConfigService(
                        store,
                        policies,
                        knowledge,
                        (model, messages, tools, temperature, tokens) -> "{}");
        configs.ensureDefault("tenant");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var draft =
                    knowledge.create(
                            "tenant",
                            "author",
                            Map.of(
                                    "title",
                                    "Synthetic reference " + i,
                                    "content",
                                    "reference ".repeat(9500)));
            var reviewed =
                    knowledge.transition(
                            "tenant",
                            draft.id(),
                            "reviewer",
                            draft.version(),
                            "review",
                            "verified");
            knowledge.transition(
                    "tenant", draft.id(), "reviewer", reviewed.version(), "publish", "approved");
            ids.add(draft.id());
        }
        configs.create(
                "tenant",
                "author",
                Map.of(
                        "model",
                        "fixed-model",
                        "prompt",
                        "Explain facts",
                        "policy_version",
                        "policy-default-v1",
                        "deterministic_only",
                        true,
                        "knowledge_versions",
                        List.of(ids.get(0))));
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                configs.create(
                                        "tenant",
                                        "author",
                                        Map.of(
                                                "model",
                                                "fixed-model",
                                                "prompt",
                                                "Explain facts",
                                                "policy_version",
                                                "policy-default-v1",
                                                "deterministic_only",
                                                true,
                                                "knowledge_versions",
                                                ids)));
        assertEquals(422, failure.status());
        assertEquals("RUNTIME_BUNDLE_TOO_LARGE", failure.code());
        assertEquals(2, store.summaryPage("tenant", "runtime_config", "", 100).size());
    }

    private static PlatformStore.Entity report(PlatformStore.Entity config, String hash) {
        return new PlatformStore.Entity(
                "report",
                "tenant",
                "eval_run",
                1,
                config.createdAt(),
                config.updatedAt(),
                Map.of(
                        "status",
                        "COMPLETED",
                        "hard_gate_passed",
                        true,
                        "runtime_config_id",
                        config.id(),
                        "resolved_config",
                        Map.of("config_hash", hash)));
    }
}
