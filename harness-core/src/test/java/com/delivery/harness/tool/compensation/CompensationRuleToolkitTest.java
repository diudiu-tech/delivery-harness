package com.delivery.harness.tool.compensation;

import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.tool.gateway.ToolGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompensationRuleToolkitTest {
    private ToolGateway gateway;
    private RuleBaseService rules;
    @BeforeEach
    void init() throws Exception {
        gateway = new ToolGateway(100);
        rules = new RuleBaseService();
        try (var stream = getClass().getResourceAsStream("/seed/rules.json")) {
            List<RuleInfo> seed = JsonUtil.fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8), new TypeReference<>() {});
            seed.forEach(rules::save);
        }
        new CompensationRuleToolkit(gateway, rules).init();
    }
    @Test
    void secondBoundariesFollowExplicitFloorPolicy() {
        long[] seconds = {0, 1, 59, 60, 899, 900, 901, 959, 960, 1799, 1800, 1801, 1859, 1860};
        String[] ids = {"COMP-006", "COMP-006", "COMP-006", "COMP-003", "COMP-003", "COMP-003", "COMP-003", "COMP-003", "COMP-002", "COMP-002", "COMP-002", "COMP-002", "COMP-002", "COMP-001"};
        for (int i = 0; i < seconds.length; i++) {
            Map<String, Object> result = decide("OVERTIME", seconds[i], new BigDecimal("64.00"), Map.of());
            Map<?, ?> rule = (Map<?, ?>) ((List<?>) result.get("matched_rules")).get(0);
            assertEquals(ids[i], rule.get("rule_id"), "seconds=" + seconds[i]);
            assertEquals("DETERMINED", result.get("decision_status"));
            assertEquals("FLOOR", ((Map<?, ?>) result.get("calculation")).get("minute_rounding"));
        }
    }
    @Test
    void missingEvidenceAndUnsupportedPolicyNeverBecomeZeroAmountDecisions() {
        assertNull(decide("OVERTIME", null, new BigDecimal("64"), Map.of()).get("authoritative_amount"));
        assertNull(decide("OVERTIME", -1L, new BigDecimal("64"), Map.of()).get("authoritative_amount"));
        Map<String, Object> missing = decide("MISSING_ITEM", 0L, new BigDecimal("64"), Map.of());
        assertEquals("OUT_OF_SCOPE", missing.get("decision_status"));
        assertNull(missing.get("authoritative_amount"));
        for (Object raw : List.of("bad", "-1", "0.001")) {
            Map<String, Object> result = decide("OVERTIME", 1860L, raw, Map.of());
            assertEquals("INSUFFICIENT_EVIDENCE", result.get("decision_status"));
            assertNull(result.get("authoritative_amount"));
        }
    }
    @Test
    void unverifiedDamagePreservesOnlyV1Candidate() {
        Map<String, Object> result = decide("DAMAGED", 0L, new BigDecimal("64"), Map.of("damage_level", "FULL"));
        assertEquals(new BigDecimal("64.00"), result.get("suggested_amount"));
        assertNull(result.get("authoritative_amount"));
        assertEquals("INSUFFICIENT_EVIDENCE", result.get("decision_status"));
        Map<String, Object> verified = decide("DAMAGED", 0L, new BigDecimal("64"), Map.of("damage_level", "PARTIAL", "damage_evidence_verified", true));
        assertEquals(new BigDecimal("20.00"), verified.get("authoritative_amount"));
        assertEquals("DETERMINED", verified.get("decision_status"));
    }
    @Test
    void equalPriorityOverlapIsAConflictAndNotAnArbitraryFirstMatch() {
        RuleInfo duplicate = rules.findById("COMP-002").orElseThrow();
        duplicate.setRuleId("COMP-999");
        rules.save(duplicate);
        Map<String, Object> result = decide("OVERTIME", 960L, new BigDecimal("64"), Map.of());
        assertEquals("POLICY_CONFLICT", result.get("decision_status"));
        assertNull(result.get("authoritative_amount"));
        assertEquals(2, ((List<?>) result.get("matched_rules")).size());
    }
    private Map<String, Object> decide(String type, Long seconds, Object amount, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>(extra);
        p.put("complaint_type", type); p.put("overtime_seconds", seconds); p.put("order_amount", amount);
        return (Map<String, Object>) gateway.invoke("compensation_rule", p).getData();
    }
}
