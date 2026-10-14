package com.delivery.harness.knowledge.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.knowledge.casemgmt.CaseBaseService;
import com.delivery.harness.knowledge.rule.RuleBaseService;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeSeedLoaderTest {
    @Test
    void syntheticKnowledgeIsLoadedOnlyWhenDemoExplicitlyEnablesIt() {
        RuleBaseService rules = new RuleBaseService();
        CaseBaseService cases = new CaseBaseService();
        KnowledgeSeedLoader loader = new KnowledgeSeedLoader(rules, cases);
        ReflectionTestUtils.setField(loader, "seedEnabled", true);
        ReflectionTestUtils.setField(loader, "mode", "demo");
        loader.loadSeedData();
        assertFalse(rules.findAll().isEmpty());
        assertFalse(cases.findAll().isEmpty());
    }

    @Test
    void pilotCannotLoadSyntheticSeedEvenWhenTheFlagIsAccidentallyEnabled() {
        for (String mode : new String[] {"pilot", "unknown"}) {
            RuleBaseService rules = new RuleBaseService();
            CaseBaseService cases = new CaseBaseService();
            KnowledgeSeedLoader loader = new KnowledgeSeedLoader(rules, cases);
            ReflectionTestUtils.setField(loader, "seedEnabled", true);
            ReflectionTestUtils.setField(loader, "mode", mode);
            loader.loadSeedData();
            assertTrue(rules.findAll().isEmpty());
            assertEquals(0, cases.findAll().size());
        }
    }

    @Test
    void disabledSeedRemainsEmptyInDemo() {
        RuleBaseService rules = new RuleBaseService();
        CaseBaseService cases = new CaseBaseService();
        new KnowledgeSeedLoader(rules, cases).loadSeedData();
        assertTrue(rules.findAll().isEmpty());
        assertTrue(cases.findAll().isEmpty());
    }
}
