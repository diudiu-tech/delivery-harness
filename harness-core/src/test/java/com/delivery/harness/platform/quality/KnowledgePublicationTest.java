package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.store.PlatformException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class KnowledgePublicationTest {
    @Test
    void independentPublicationPreservesReferenceMetadataAndWithdrawalStopsSearch() {
        var store = PlatformTestDatabase.memory();
        var knowledge =
                new KnowledgeService(store, new PolicyService(store, new RuleBaseService()));
        var draft =
                knowledge.create(
                        "tenant",
                        "author",
                        Map.of(
                                "title",
                                "reference",
                                "content",
                                "uniquefact",
                                "source_type",
                                "case",
                                "tags",
                                List.of("overtime")));
        assertEquals("case", draft.data().get("source_type"));
        assertEquals(List.of("overtime"), draft.data().get("tags"));
        assertThrows(
                PlatformException.class,
                () ->
                        knowledge.transition(
                                "tenant",
                                draft.id(),
                                "author",
                                draft.version(),
                                "review",
                                "reviewed"));
        var reviewed =
                knowledge.transition(
                        "tenant", draft.id(), "reviewer", draft.version(), "review", "reviewed");
        var published =
                knowledge.transition(
                        "tenant", draft.id(), "reviewer", reviewed.version(), "publish", "checked");
        assertEquals(
                1, knowledge.search("tenant", Map.of("query", "uniquefact")).get("total_found"));
        knowledge.transition(
                "tenant", draft.id(), "reviewer", published.version(), "withdraw", "superseded");
        assertEquals(
                0, knowledge.search("tenant", Map.of("query", "uniquefact")).get("total_found"));
    }
}
