package com.delivery.harness.agent.guardrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
