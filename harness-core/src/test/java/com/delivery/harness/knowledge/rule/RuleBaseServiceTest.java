package com.delivery.harness.knowledge.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.dto.RuleInfo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RuleBaseServiceTest {
    @Test
    void publishedVersionIsImmutableAndSnapshotsCannotBeMutatedByCallers() {
        RuleBaseService service = new RuleBaseService();
        RuleInfo rule = rule("X");
        service.save(rule);
        rule.getActions().put("amount", 99);
        assertEquals(5, service.findById("X").orElseThrow().getActions().get("amount"));
        RuleInfo update = service.findById("X").orElseThrow();
        update.getActions().put("amount", 10);
        assertThrows(IllegalArgumentException.class, () -> service.save(update));
        update.setVersion("2.0.0");
        service.save(update);
        assertEquals(5, service.findVersion("X", "1.0.0").orElseThrow().getActions().get("amount"));
    }

    @Test
    void publishedEffectiveWindowFiltersOldAndFuturePolicies() {
        RuleBaseService service = new RuleBaseService();
        RuleInfo policy = rule("X");
        policy.setEffectiveFrom(Instant.parse("2026-10-01T00:00:00Z"));
        service.save(policy);
        assertTrue(
                service.findByTypeAt("compensation", Instant.parse("2026-09-30T00:00:00Z"))
                        .isEmpty());
        assertEquals(
                1,
                service.findByTypeAt("compensation", Instant.parse("2026-10-01T00:00:00Z")).size());
    }

    @Test
    void publicationDetectsOverlappingPoliciesAndRejectsInvalidAmounts() {
        RuleInfo a = rule("X");
        RuleInfo b = rule("Y");
        assertEquals(1, RuleBaseService.publicationConflicts(List.of(a, b)).size());
        a.getActions().put("amount", "0.001");
        assertThrows(IllegalArgumentException.class, () -> new RuleBaseService().save(a));
    }

    @Test
    void methodContractRejectsMissingUnknownAndContradictoryActions() {
        RuleInfo missing = rule("MISSING");
        missing.getActions().remove("method");
        assertThrows(IllegalArgumentException.class, () -> new RuleBaseService().save(missing));
        RuleInfo unknown = rule("UNKNOWN");
        unknown.getActions().put("method", "CASH");
        assertThrows(IllegalArgumentException.class, () -> new RuleBaseService().save(unknown));
        RuleInfo contradictory = rule("NONE");
        contradictory.getActions().put("method", "NONE");
        assertThrows(
                IllegalArgumentException.class, () -> new RuleBaseService().save(contradictory));
    }

    private static RuleInfo rule(String id) {
        return RuleInfo.builder()
                .ruleId(id)
                .ruleType("compensation")
                .priority(10)
                .version("1.0.0")
                .conditions(
                        new LinkedHashMap<>(
                                Map.of(
                                        "complaint_type",
                                        "OVERTIME",
                                        "min_overtime_minutes",
                                        1,
                                        "max_overtime_minutes",
                                        15)))
                .actions(
                        new LinkedHashMap<>(
                                Map.of("amount_type", "FIXED", "amount", 5, "method", "COUPON")))
                .build();
    }
}
