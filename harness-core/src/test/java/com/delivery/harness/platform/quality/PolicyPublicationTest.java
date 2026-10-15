package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
