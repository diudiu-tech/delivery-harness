package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.store.PlatformException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class PolicyPublicationTest {
    @Test
    void compensationMethodsAreCanonicalAndUnknownMethodsCannotCreateDrafts() {
        var service = new PolicyService(PlatformTestDatabase.memory(), new RuleBaseService());
        var valid = service.create("tenant", "author", Map.of("rules", List.of(rule("退款"))));
        var actions =
                QualityData.map(
                        PolicyService.ruleMaps(valid.data().get("rules")).get(0).get("actions"));
        assertEquals("REFUND", actions.get("method"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create(
                                "tenant",
                                "author",
                                Map.of("rules", List.of(rule("MONEY_BACK_SOMETHING")))));
    }

    @Test
    void authorsCannotPublishTheirOwnValidatedPolicies() {
        var service = new PolicyService(PlatformTestDatabase.memory(), new RuleBaseService());
        var draft = service.create("tenant", "author", Map.of("rules", List.of(rule("COUPON"))));
        assertEquals(
                403,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        service.publish(
                                                "tenant", draft.id(), "author", draft.version()))
                        .status());
        var published =
                service.publish("tenant", draft.id(), "independent-publisher", draft.version());
        assertEquals("PUBLISHED", published.data().get("state"));
    }

    @Test
    void overlappingPublicationIsRejectedUnlessTheReplacementDeclaresSupersession() {
        var store = PlatformTestDatabase.memory();
        var service = new PolicyService(store, new RuleBaseService());
        var first =
                service.create(
                        "tenant",
                        "author",
                        Map.of("rules", List.of(rule("COUPON")), "city_codes", List.of("010")));
        service.publish("tenant", first.id(), "publisher", first.version());
        var overlapping =
                service.create(
                        "tenant",
                        "author",
                        Map.of(
                                "rules",
                                List.of(rule("COUPON")),
                                "effective_from",
                                "2026-01-01T00:00:00Z"));
        var conflict =
                assertThrows(
                        PlatformException.class,
                        () ->
                                service.publish(
                                        "tenant",
                                        overlapping.id(),
                                        "publisher",
                                        overlapping.version()));
        assertEquals(409, conflict.status());
        assertEquals("POLICY_OVERLAP", conflict.code());
        assertTrue(conflict.getMessage().contains(first.id()));
        assertEquals(
                "DRAFT",
                store.require("tenant", PolicyService.KIND, overlapping.id()).data().get("state"));
        // Disjoint scope or effective window does not overlap.
        var otherCity =
                service.create(
                        "tenant",
                        "author",
                        Map.of("rules", List.of(rule("COUPON")), "city_codes", List.of("020")));
        service.publish("tenant", otherCity.id(), "publisher", otherCity.version());
        var replacement =
                service.create(
                        "tenant",
                        "author",
                        Map.of(
                                "rules",
                                List.of(rule("COUPON")),
                                "city_codes",
                                List.of("010"),
                                "supersedes_policy_ids",
                                List.of(first.id())));
        service.publish("tenant", replacement.id(), "publisher", replacement.version());
        assertEquals(
                "SUPERSEDED",
                store.require("tenant", PolicyService.KIND, first.id()).data().get("state"));
        assertEquals(
                "PUBLISHED",
                store.require("tenant", PolicyService.KIND, otherCity.id()).data().get("state"));
        // Runtimes already pinned to the superseded version keep their frozen rules.
        assertEquals(1, service.resolve("tenant", first.id()).size());
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        service.publish(
                                                "tenant",
                                                overlapping.id(),
                                                "publisher",
                                                overlapping.version()))
                        .status());
    }

    @Test
    void onlyPublishedOrSupersededPoliciesCanBeWithdrawn() {
        var service = new PolicyService(PlatformTestDatabase.memory(), new RuleBaseService());
        var draft = service.create("tenant", "author", Map.of("rules", List.of(rule("COUPON"))));
        var notLive =
                assertThrows(
                        PlatformException.class,
                        () -> service.withdraw("tenant", draft.id(), "p", draft.version(), "x"));
        assertEquals(409, notLive.status());
        assertEquals("POLICY_NOT_WITHDRAWABLE", notLive.code());
        var published = service.publish("tenant", draft.id(), "publisher", draft.version());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.withdraw("tenant", draft.id(), "p", published.version(), " "));
        var withdrawn =
                service.withdraw("tenant", draft.id(), "p", published.version(), "regression");
        assertEquals("WITHDRAWN", withdrawn.data().get("state"));
        assertEquals(
                "POLICY_NOT_WITHDRAWABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        service.withdraw(
                                                "tenant",
                                                draft.id(),
                                                "p",
                                                withdrawn.version(),
                                                "again"))
                        .code());
    }

    private static Map<String, Object> rule(String method) {
        return JsonUtil.toMap(
                RuleInfo.builder()
                        .ruleId("POLICY-1")
                        .ruleName("policy")
                        .ruleType("compensation")
                        .enabled(true)
                        .conditions(Map.of("complaint_type", "OVERTIME", "min_overtime_minutes", 0))
                        .actions(Map.of("amount_type", "FIXED", "amount", "3.00", "method", method))
                        .build());
    }
}
