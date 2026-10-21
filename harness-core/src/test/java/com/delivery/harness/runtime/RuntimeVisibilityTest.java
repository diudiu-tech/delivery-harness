package com.delivery.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class RuntimeVisibilityTest {
    @Test
    void globalPolicyDoesNotGrantAccessToOtherStationsFrozenKnowledge() {
        var config =
                Map.<String, Object>of(
                        "policy_scope",
                        scope(),
                        "knowledge_snapshot",
                        List.of(
                                Map.of(
                                        "city_codes",
                                        List.of("010"),
                                        "station_ids",
                                        List.of("S002"),
                                        "content",
                                        "private")));
        assertFalse(RuntimeVisibility.canRead(config, Set.of("010"), Set.of("S001")));
        assertTrue(RuntimeVisibility.canRead(config, Set.of("010"), Set.of("S002")));
        assertTrue(RuntimeVisibility.canRead(config, Set.of("*"), Set.of("*")));
    }

    @Test
    void missingOrMalformedScopeNeverDefaultsToGlobalEvenForAnAdministrator() {
        var all = Set.of("*");
        assertFalse(RuntimeVisibility.canRead(Map.of(), all, all));
        assertFalse(
                RuntimeVisibility.canRead(
                        Map.of("policy_scope", Map.of(), "knowledge_snapshot", List.of()),
                        all,
                        all));
        assertFalse(
                RuntimeVisibility.canRead(
                        Map.of("policy_scope", scope(), "knowledge_snapshot", List.of(Map.of())),
                        all,
                        all));
        assertFalse(
                RuntimeVisibility.canRead(
                        Map.of("policy_scope", scope(), "knowledge_snapshot", "invalid"),
                        all,
                        all));
        assertFalse(
                RuntimeVisibility.canRead(
                        Map.of(
                                "policy_scope",
                                Map.of("city_codes", 42, "station_ids", List.of()),
                                "knowledge_snapshot",
                                List.of()),
                        all,
                        all));
    }

    @Test
    void everyFrozenDocumentMustBeVisibleWhileAnExplicitGlobalScopeRemainsReadable() {
        var config =
                Map.<String, Object>of(
                        "policy_scope", scope(), "knowledge_snapshot", List.of(scope()));
        assertTrue(RuntimeVisibility.canRead(config, Set.of("010"), Set.of("S001")));
        var mixed =
                Map.<String, Object>of(
                        "policy_scope",
                        scope(),
                        "knowledge_snapshot",
                        List.of(
                                scope(),
                                Map.of(
                                        "city_codes",
                                        List.of("020"),
                                        "station_ids",
                                        List.of("S002"))));
        assertFalse(RuntimeVisibility.canRead(mixed, Set.of("010"), Set.of("S001")));
    }

    private static Map<String, Object> scope() {
        return Map.of("city_codes", List.of(), "station_ids", List.of());
    }
}
