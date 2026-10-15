package com.delivery.harness.eval.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.delivery.harness.eval.casemanager.EvalCaseManager;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EvalSeedLoaderTest {
    @Test
    void pilotCannotSeedSyntheticCasesEvenWhenTheFlagIsEnabled() {
        var cases = new EvalCaseManager();
        var loader = new EvalSeedLoader(cases);
        ReflectionTestUtils.setField(loader, "seedEnabled", true);
        ReflectionTestUtils.setField(loader, "mode", "pilot");
        loader.loadSeedCases();
        assertEquals(0, cases.count());
    }

    @Test
    void demoLoadsFixturesOnlyWhenExplicitlyEnabled() {
        var cases = new EvalCaseManager();
        var loader = new EvalSeedLoader(cases);
        ReflectionTestUtils.setField(loader, "seedEnabled", false);
        loader.loadSeedCases();
        assertEquals(0, cases.count());
        ReflectionTestUtils.setField(loader, "seedEnabled", true);
        loader.loadSeedCases();
        assertFalse(cases.findAll().isEmpty());
    }
}
