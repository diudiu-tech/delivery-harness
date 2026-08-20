package com.delivery.harness.eval.casemanager;

import com.delivery.harness.common.dto.EvalCase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCaseManagerTest {

    private final EvalCaseManager manager = new EvalCaseManager();

    @Test
    void savesAValidCase() {
        manager.save(validCase());

        assertEquals(1, manager.count());
    }

    @Test
    void rejectsCasesMissingRequiredFields() {
        EvalCase missingId = validCase();
        missingId.setCaseId(null);

        EvalCase missingScenario = validCase();
        missingScenario.setScenario(" ");

        EvalCase missingInput = validCase();
        missingInput.setInput(null);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> manager.save(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> manager.save(missingId)),
                () -> assertThrows(IllegalArgumentException.class, () -> manager.save(missingScenario)),
                () -> assertThrows(IllegalArgumentException.class, () -> manager.save(missingInput))
        );
    }

    @Test
    void rejectsNullOrBlankCaseIdElements() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> manager.findByIds(null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.findByIds(Arrays.asList("case-1", null))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.findByIds(Arrays.asList("case-1", " "))
                )
        );
    }

    @Test
    void rejectsUnknownCaseIdsInsteadOfSilentlyShrinkingTheRun() {
        manager.save(validCase());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> manager.findByIds(Arrays.asList("case-1", "missing-case"))
        );

        assertTrue(error.getMessage().contains("missing-case"));
    }

    private EvalCase validCase() {
        return EvalCase.builder()
                .caseId("case-1")
                .scenario("abnormal_order_analysis")
                .input(Collections.singletonMap("order_id", "order-1"))
                .build();
    }
}
