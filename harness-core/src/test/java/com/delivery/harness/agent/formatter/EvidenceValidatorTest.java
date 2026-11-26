package com.delivery.harness.agent.formatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class EvidenceValidatorTest {
    private static final Map<String, Object> EVIDENCE =
            Map.of(
                    "timeline_v2",
                    Map.of("version", "2", "fields", Map.of("merchant_prep_seconds", 1680L)));

    @Test
    void refundBalancesUseCurrencyUnitsAndExactFrozenValues() {
        Map<String, Object> evidence =
                Map.of(
                        "order",
                        Map.of(
                                "version",
                                "1",
                                "fields",
                                Map.of("refundable_balance", new BigDecimal("10.00"))));
        Map<String, Object> ref = new LinkedHashMap<>(reference());
        ref.put("evidence_id", "order");
        ref.put("evidence_version", "1");
        ref.put("field_path", "refundable_balance");
        ref.put("observed_value", new BigDecimal("10.00"));
        ref.put("unit", "CNY");
        assertTrue(EvidenceValidator.validate(output(ref), evidence, Set.of("ANA-001")).isEmpty());
        ref.put("unit", "value");
        assertTrue(
                EvidenceValidator.validate(output(ref), evidence, Set.of("ANA-001"))
                        .contains("evidence_unit_mismatch:order:refundable_balance"));
    }

    @Test
    void acceptsOnlyExistingVersionedEvidenceWithExactValueAndUnit() {
        Map<String, Object> analysis = output(reference());
        assertTrue(EvidenceValidator.validate(analysis, EVIDENCE, Set.of("ANA-001")).isEmpty());
        for (var wrong :
                Map.<String, Object>of(
                                "observed_value",
                                1679,
                                "evidence_id",
                                "invented",
                                "evidence_version",
                                "1",
                                "unit",
                                "minutes",
                                "field_path",
                                "capacity_ratio")
                        .entrySet()) {
            Map<String, Object> ref = new LinkedHashMap<>(reference());
            ref.put(wrong.getKey(), wrong.getValue());
            assertFalse(
                    EvidenceValidator.validate(output(ref), EVIDENCE, Set.of("ANA-001")).isEmpty(),
                    wrong.getKey());
        }
    }

    @Test
    void rejectsNumericTextWhenTheCitedFieldIsANumber() {
        Map<String, Object> reference = new LinkedHashMap<>(reference());
        reference.put("observed_value", "1680");
        assertFalse(
                EvidenceValidator.validate(output(reference), EVIDENCE, Set.of("ANA-001"))
                        .isEmpty());
    }

    @Test
    void rejectsInventedRulesAndFreeTextEvidence() {
        Map<String, Object> output = output(reference());
        output.put("applicable_rules", List.of("COMP-INVENTED"));
        assertTrue(
                EvidenceValidator.validate(output, EVIDENCE, Set.of("ANA-001"))
                        .contains("rule_not_in_snapshot:COMP-INVENTED"));
        output.put("evidence_chain", List.of("free text"));
        assertTrue(
                EvidenceValidator.validate(output, EVIDENCE, Set.of()).stream()
                        .anyMatch(e -> e.contains("invalid_object")));
    }

    @Test
    void rulesAndTypedReferencesAreRequiredRatherThanSilentlyIgnored() {
        for (Object invalidRules : List.of("ANA-001", Map.of("id", "ANA-001"), 1)) {
            Map<String, Object> analysis = output(reference());
            analysis.put("applicable_rules", invalidRules);
            assertTrue(
                    EvidenceValidator.validate(analysis, EVIDENCE, Set.of("ANA-001"))
                            .contains("applicable_rules_required_list"));
        }
        Map<String, Object> absent = output(reference());
        absent.remove("applicable_rules");
        assertTrue(
                EvidenceValidator.validate(absent, EVIDENCE, Set.of("ANA-001"))
                        .contains("applicable_rules_required_list"));
        for (String field :
                List.of(
                        "fact",
                        "evidence_id",
                        "evidence_version",
                        "field_path",
                        "observed_value",
                        "unit")) {
            Map<String, Object> ref = new LinkedHashMap<>(reference());
            ref.remove(field);
            assertFalse(
                    EvidenceValidator.validate(output(ref), EVIDENCE, Set.of("ANA-001")).isEmpty(),
                    field);
        }
        Map<String, Object> untyped = new LinkedHashMap<>(reference());
        untyped.put("evidence_id", 1);
        assertFalse(
                EvidenceValidator.validate(output(untyped), EVIDENCE, Set.of("ANA-001")).isEmpty());
    }

    private static Map<String, Object> output(Map<String, Object> ref) {
        return new LinkedHashMap<>(
                Map.of("evidence_chain", List.of(ref), "applicable_rules", List.of("ANA-001")));
    }

    private static Map<String, Object> reference() {
        return Map.of(
                "fact",
                "Merchant prep lasted 28 minutes",
                "evidence_id",
                "timeline_v2",
                "evidence_version",
                "2",
                "field_path",
                "merchant_prep_seconds",
                "observed_value",
                1680,
                "unit",
                "seconds");
    }
}
