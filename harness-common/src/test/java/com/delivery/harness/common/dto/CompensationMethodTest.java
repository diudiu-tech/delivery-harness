package com.delivery.harness.common.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

class CompensationMethodTest {
    @Test
    void aliasesPreserveTheSameFinancialMeaning() {
        assertEquals(CompensationMethod.REFUND, CompensationMethod.fromPolicy("原路退款"));
        assertTrue(CompensationMethod.fromPolicy("refund").isCashRefund());
        assertFalse(CompensationMethod.fromPolicy("发放优惠券").isCashRefund());
        assertEquals(
                CompensationMethod.RESEND_AND_COUPON, CompensationMethod.fromPolicy("补送并发放优惠券"));
        assertFalse(CompensationMethod.RESEND.isMonetary());
        assertThrows(IllegalArgumentException.class, () -> CompensationMethod.fromPolicy("CASH"));
        assertThrows(IllegalArgumentException.class, () -> CompensationMethod.fromPolicy(null));
    }

    @Test
    void nonmonetaryAndUndeterminedMethodsCannotHideAPositiveCashDecision() {
        assertEquals(
                CompensationMethod.NONE,
                CompensationMethod.fromAction(
                        Map.of("method", "NONE", "amount_type", "FIXED", "amount", "0.00")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompensationMethod.fromAction(
                                Map.of("method", "NONE", "amount_type", "FIXED", "amount", 5)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompensationMethod.fromAction(
                                Map.of("method", "MANUAL", "amount_type", "FIXED", "amount", 5)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompensationMethod.fromAction(
                                Map.of("method", "REFUND", "amount_type", "MANUAL")));
    }
}
