package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        PlatformStore.Entity passing =
                report(candidate, candidate.data().get("config_hash").toString());
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
        assertEquals(
                candidate.data().get("config_hash"),
                configs.resolve("tenant", candidate.id()).get("config_hash"));
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
