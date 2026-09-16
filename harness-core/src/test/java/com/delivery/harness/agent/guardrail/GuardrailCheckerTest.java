package com.delivery.harness.agent.guardrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailCheckerTest {

    private GuardrailChecker checker;

    @BeforeEach
    void setUp() {
        checker = new GuardrailChecker();
        ReflectionTestUtils.setField(checker, "maxCompensationAmount", 50.0);
    }

    @Test
    void acceptsExpectedAbnormalOrderShape() {
        assertTrue(checker.check("{\"primary_cause\":\"traffic\"}", "abnormal_order_analysis"));
    }

    @Test
    void rejectsMissingRequiredFieldAndForbiddenPromise() {
        assertFalse(checker.check("{\"reason\":\"我们承诺一定赔偿\"}", "abnormal_order_analysis"));
    }

    @Test
    void rejectsCompensationAboveConfiguredMaximum() {
        assertFalse(checker.checkCompensation("{\"suggested_amount\":50.01}"));
        assertTrue(checker.checkCompensation("{\"suggested_amount\":50.0}"));
    }

    @Test
    void rejectsMissingMalformedAndNegativeAmounts() {
        assertFalse(checker.checkCompensation("{}"));
        assertFalse(checker.checkCompensation("{\"suggested_amount\":\"10\"}"));
        assertFalse(checker.checkCompensation("{\"suggested_amount\":-1}"));
    }

    @Test
    void parsesScientificNotationWithoutPrefixBypass() {
        assertFalse(checker.checkCompensation("{\"suggested_amount\":1e9}"));
        assertTrue(checker.checkCompensation("{\"suggested_amount\":5e1}"));
    }

    @Test
    void detectsAmountProposalsInFieldsAndProse() {
        assertTrue(checker.mentionsCompensationAmount("{\"suggested_amount\":20}"));
        assertTrue(checker.mentionsCompensationAmount("{\"reason\":\"建议赔付20元\"}"));
        assertTrue(checker.mentionsCompensationAmount("{\"reason\":\"建议赔付￥ 20.50\"}"));
        assertFalse(checker.mentionsCompensationAmount("{\"reason\":\"订单晚了20分钟\"}"));
    }

    @Test
    void allowsTheAuthoritativeAmountToBeQuotedButRejectsConflicts() {
        BigDecimal authoritative = new BigDecimal("20.00");

        assertFalse(checker.mentionsConflictingCompensationAmount(
                "适用 COMP-001，最高不超过20元", authoritative));
        assertFalse(checker.mentionsConflictingCompensationAmount(
                "规则引擎已判定本单赔付 ￥20.00，我仅说明依据", authoritative));
        assertFalse(checker.mentionsConflictingCompensationAmount(
                "{\"suggested_amount\":20}", authoritative));
        assertTrue(checker.mentionsConflictingCompensationAmount(
                "{\"suggested_amount\":\"20\"}", authoritative));
        assertTrue(checker.mentionsConflictingCompensationAmount(
                "建议赔付50元", authoritative));
        assertTrue(checker.mentionsConflictingCompensationAmount(
                "{\"suggested_amount\":999}", authoritative));
        assertFalse(checker.mentionsConflictingCompensationAmount(
                "订单晚了35分钟", authoritative));
    }

    @Test
    void treatsUnparseableExponentsAsConflicts() {
        BigDecimal authoritative = new BigDecimal("20.00");
        for (String amount : new String[]{"1e2147483648", "1e-2147483648", "1e99999999999999999999"}) {
            assertTrue(checker.mentionsConflictingCompensationAmount(
                    "{\"suggested_amount\":" + amount + "}", authoritative));
        }
    }

    @Test
    void comparesValidScientificNotationWithTheAuthoritativeAmount() {
        BigDecimal authoritative = new BigDecimal("20.00");
        assertFalse(checker.mentionsConflictingCompensationAmount(
                "{\"suggested_amount\":2e1}", authoritative));
        assertTrue(checker.mentionsConflictingCompensationAmount(
                "{\"suggested_amount\":3e1}", authoritative));
    }

    @Test
    void preservesCurrencyFormsAndAuthoritativeAmountComparison() {
        for (String amount : new String[]{"20元", "20.元", "20.00 元", "¥20", "￥ 20.00"}) {
            assertTrue(checker.mentionsCompensationAmount(amount), amount);
            assertFalse(checker.mentionsConflictingCompensationAmount(
                    amount, new BigDecimal("20")), amount);
            assertTrue(checker.mentionsConflictingCompensationAmount(
                    amount, new BigDecimal("10")), amount);
        }
        for (String amount : new String[]{"-.5元", "-0.50 元", "¥-.5", "￥ -0.50"}) {
            assertTrue(checker.mentionsCompensationAmount(amount), amount);
            assertFalse(checker.mentionsConflictingCompensationAmount(
                    amount, new BigDecimal("-0.5")), amount);
            assertTrue(checker.mentionsConflictingCompensationAmount(
                    amount, new BigDecimal("0.5")), amount);
        }
    }

    @Test
    void checksLongDigitRunsWithoutQuadraticScanning() {
        String digits = "9".repeat(40000);
        // Generous budget for slow CI; the old expression takes seconds per scan.
        assertTimeout(Duration.ofSeconds(2), () -> {
            for (String content : new String[]{digits, "-" + digits,
                    digits + "." + digits, digits + " minutes"}) {
                assertFalse(checker.mentionsCompensationAmount(content));
                assertFalse(checker.mentionsConflictingCompensationAmount(
                        content, new BigDecimal("20")));
            }
            assertTrue(checker.mentionsCompensationAmount(digits + "元"));
            assertTrue(checker.mentionsCompensationAmount("￥" + digits));
            assertFalse(checker.mentionsConflictingCompensationAmount(
                    digits + "；赔付20元", new BigDecimal("20")));
            assertTrue(checker.mentionsConflictingCompensationAmount(
                    digits + "；赔付30元", new BigDecimal("20")));
        });
    }
}
