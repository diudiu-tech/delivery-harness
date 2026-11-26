package com.delivery.harness.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

class RedactionTest {
    @Test
    void refundableBalanceIsPublicButPrivateOrderFieldsAndArbitraryExtrasAreExcluded() {
        Map<String, Object> value =
                Redaction.map(
                        Map.of(
                                "orderId",
                                "order",
                                "status",
                                "DELIVERED",
                                "refundableBalance",
                                new BigDecimal("3.00"),
                                "userId",
                                "private",
                                "unknownPII",
                                "private",
                                "extraInfo",
                                Map.of("weather", "rain", "phone", "private")));
        assertEquals(new BigDecimal("3.00"), value.get("refundableBalance"));
        assertFalse(value.containsKey("userId"));
        assertFalse(value.containsKey("unknownPII"));
        assertEquals(Map.of("weather", "rain"), value.get("extraInfo"));
    }

    @Test
    void recursivePublicViewsNeverContainRawModelOutputAliases() {
        Map<String, Object> value =
                Redaction.map(
                        Map.of(
                                "nested",
                                Map.of(
                                        "rawModelOutput",
                                        "private",
                                        "modelRawOutput",
                                        "private",
                                        "raw_model_output",
                                        "private",
                                        "model_raw_output",
                                        "private")));
        assertTrue(((Map<?, ?>) value.get("nested")).isEmpty());
    }
}
