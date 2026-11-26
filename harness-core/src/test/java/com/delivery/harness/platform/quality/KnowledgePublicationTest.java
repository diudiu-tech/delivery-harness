package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

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
        var withdrawn =
                knowledge.transition(
                        "tenant",
                        draft.id(),
                        "reviewer",
                        published.version(),
                        "withdraw",
                        "superseded");
        assertEquals(
                0, knowledge.search("tenant", Map.of("query", "uniquefact")).get("total_found"));
        var again =
                assertThrows(
                        PlatformException.class,
                        () ->
                                knowledge.transition(
                                        "tenant",
                                        draft.id(),
                                        "reviewer",
                                        withdrawn.version(),
                                        "withdraw",
                                        "twice"));
        assertEquals(409, again.status());
        assertEquals("KNOWLEDGE_NOT_WITHDRAWABLE", again.code());
        var other = knowledge.create("tenant", "author", document(null));
        assertEquals(
                "KNOWLEDGE_NOT_WITHDRAWABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        knowledge.transition(
                                                "tenant",
                                                other.id(),
                                                "reviewer",
                                                other.version(),
                                                "withdraw",
                                                "draft"))
                        .code());
    }

    @Test
    void publishingANewDocumentVersionSupersedesThePreviousCurrentVersion() {
        var store = PlatformTestDatabase.memory();
        var knowledge =
                new KnowledgeService(store, new PolicyService(store, new RuleBaseService()));
        var first = publish(knowledge, knowledge.create("tenant", "author", document(null)));
        String documentId = first.data().get("document_id").toString();
        var second = knowledge.create("tenant", "author", document(documentId));
        assertEquals(2L, ((Number) second.data().get("document_version")).longValue());
        second = publish(knowledge, second);
        var superseded = store.require("tenant", KnowledgeService.KIND, first.id());
        assertEquals("SUPERSEDED", superseded.data().get("state"));
        assertEquals(second.id(), superseded.data().get("superseded_by_version"));
        var found = knowledge.search("tenant", Map.of("query", "versionedfact"));
        assertEquals(1, found.get("total_found"));
        assertEquals(
                second.id(),
                PolicyService.ruleMaps(found.get("items")).get(0).get("version_id"));
        assertEquals(
                "SUPERSEDED",
                knowledge.citation("tenant", first.id() + ":0").get("current_state"));
        // Runtimes that pinned the old version can still resolve its frozen content.
        assertEquals(1, knowledge.freeze("tenant", List.of(first.id())).size());
        // An older reviewed version cannot displace the current publication.
        var third = knowledge.create("tenant", "author", document(documentId));
        var fourth = publish(knowledge, knowledge.create("tenant", "author", document(documentId)));
        var lateReview =
                knowledge.transition(
                        "tenant", third.id(), "reviewer", third.version(), "review", "late");
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        knowledge.transition(
                                                "tenant",
                                                third.id(),
                                                "reviewer",
                                                lateReview.version(),
                                                "publish",
                                                "stale"))
                        .status());
        assertEquals(
                "PUBLISHED",
                store.require("tenant", KnowledgeService.KIND, fourth.id()).data().get("state"));
    }

    @Test
    void reusedDocumentIdsMustExistInTheSameScopeAndVersionsStayUnique() throws Exception {
        var store = PlatformTestDatabase.memory();
        var knowledge =
                new KnowledgeService(store, new PolicyService(store, new RuleBaseService()));
        Map<String, Object> cityB = new LinkedHashMap<>(document(null));
        cityB.put("city_codes", List.of("city-b"));
        String documentId =
                knowledge.create("tenant", "author", cityB).data().get("document_id").toString();
        Map<String, Object> cityA = new LinkedHashMap<>(document(documentId));
        cityA.put("city_codes", List.of("city-a"));
        var wrongScope =
                assertThrows(
                        PlatformException.class, () -> knowledge.create("tenant", "a", cityA));
        var missing =
                assertThrows(
                        PlatformException.class,
                        () -> knowledge.create("tenant", "a", document("never-created")));
        assertEquals(404, wrongScope.status());
        assertEquals(404, missing.status());
        assertEquals(wrongScope.getMessage(), missing.getMessage());
        Map<String, Object> next = new LinkedHashMap<>(document(documentId));
        next.put("city_codes", List.of("city-b"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> versions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                versions.add(
                        pool.submit(
                                () -> {
                                    while (true) {
                                        try {
                                            return ((Number)
                                                            knowledge
                                                                    .create("tenant", "b", next)
                                                                    .data()
                                                                    .get("document_version"))
                                                    .longValue();
                                        } catch (PlatformException conflict) {
                                            if (conflict.status() != 409) throw conflict;
                                        }
                                    }
                                }));
            }
            Set<Long> allocated = new TreeSet<>();
            for (Future<Long> version : versions) allocated.add(version.get());
            assertEquals(Set.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), allocated);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void migrationLeavesOneCurrentPublicationPerLegacyDocument() {
        DataSource source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        // Stop just before V12; the current store writes V11 lease columns.
        Flyway.configure().dataSource(source).target("11").load().migrate();
        var store = new PlatformStore(source);
        for (int version = 1; version <= 2; version++) {
            Map<String, Object> legacy = new LinkedHashMap<>(document("legacy-doc"));
            legacy.put("state", "PUBLISHED");
            legacy.put("document_version", version);
            legacy.put(
                    "chunks",
                    List.of(
                            Map.of(
                                    "chunk_id",
                                    "legacy-v" + version + ":0",
                                    "title_path",
                                    "legacy",
                                    "content",
                                    "versionedfact")));
            store.create("tenant", KnowledgeService.KIND, "legacy-v" + version, legacy, "a", "T");
        }
        PlatformTestDatabase.migrate(source);
        assertEquals(
                "SUPERSEDED",
                store.require("tenant", KnowledgeService.KIND, "legacy-v1").data().get("state"));
        assertEquals(
                "PUBLISHED",
                store.require("tenant", KnowledgeService.KIND, "legacy-v2").data().get("state"));
        var knowledge =
                new KnowledgeService(store, new PolicyService(store, new RuleBaseService()));
        assertEquals(
                1, knowledge.search("tenant", Map.of("query", "versionedfact")).get("total_found"));
        assertEquals(
                3L,
                ((Number)
                                knowledge
                                        .create("tenant", "author", document("legacy-doc"))
                                        .data()
                                        .get("document_version"))
                        .longValue());
    }

    @Test
    void migrationSplitsLegacyDocumentsWhoseVersionsSpanScopes() {
        DataSource source = legacyDatabase();
        var store = new PlatformStore(source);
        legacyVersion(store, "city-a-v1", "shared-doc", 1, "PUBLISHED", "city-a");
        legacyVersion(store, "city-b-v2", "shared-doc", 2, "PUBLISHED", "city-b");
        legacyVersion(store, "city-b-v3", "shared-doc", 3, "PUBLISHED", "city-b");
        PlatformTestDatabase.migrate(source);
        var cityA = store.require("tenant", KnowledgeService.KIND, "city-a-v1").data();
        var cityB = store.require("tenant", KnowledgeService.KIND, "city-b-v3").data();
        // Each scope keeps its own live publication; only same-scope duplicates are superseded.
        assertEquals("PUBLISHED", cityA.get("state"));
        assertEquals("PUBLISHED", cityB.get("state"));
        assertEquals(
                "SUPERSEDED",
                store.require("tenant", KnowledgeService.KIND, "city-b-v2").data().get("state"));
        assertEquals("shared-doc", cityB.get("document_id"));
        String splitId = cityA.get("document_id").toString();
        assertNotEquals("shared-doc", splitId);
        assertEquals(
                splitId,
                PolicyService.ruleMaps(cityA.get("chunks")).get(0).get("document_id"));
        assertEquals(
                "city-a-v1:0", PolicyService.ruleMaps(cityA.get("chunks")).get(0).get("chunk_id"));
        var splitHead = store.require("tenant", KnowledgeService.DOCUMENT, splitId).data();
        assertEquals(List.of("city-a"), splitHead.get("city_codes"));
        assertEquals("city-a-v1", splitHead.get("current_version_id"));
        assertEquals(
                List.of("city-b"),
                store.require("tenant", KnowledgeService.DOCUMENT, "shared-doc")
                        .data()
                        .get("city_codes"));
        assertTrue(
                store.audits("tenant").stream()
                        .anyMatch(a -> "KNOWLEDGE_DOCUMENT_SPLIT".equals(a.get("action"))));
        var knowledge =
                new KnowledgeService(store, new PolicyService(store, new RuleBaseService()));
        for (String city : List.of("city-a", "city-b")) {
            assertEquals(
                    1,
                    knowledge.search(
                                    "tenant", Map.of("query", "versionedfact", "city_code", city))
                            .get("total_found"));
        }
        Map<String, Object> continuation = new LinkedHashMap<>(document(splitId));
        continuation.put("city_codes", List.of("city-a"));
        assertEquals(
                2L,
                ((Number)
                                knowledge
                                        .create("tenant", "author", continuation)
                                        .data()
                                        .get("document_version"))
                        .longValue());
    }

    @Test
    void migrationHeadScopeFollowsThePublishedVersionNotALaterDraft() {
        DataSource source = legacyDatabase();
        var store = new PlatformStore(source);
        legacyVersion(store, "live-v1", "drafted-doc", 1, "PUBLISHED", "city-a");
        legacyVersion(store, "draft-v2", "drafted-doc", 2, "DRAFT", "city-b");
        PlatformTestDatabase.migrate(source);
        var head = store.require("tenant", KnowledgeService.DOCUMENT, "drafted-doc").data();
        assertEquals(List.of("city-a"), head.get("city_codes"));
        assertEquals("live-v1", head.get("current_version_id"));
        var draft = store.require("tenant", KnowledgeService.KIND, "draft-v2").data();
        assertNotEquals("drafted-doc", draft.get("document_id"));
        var knowledge =
                new KnowledgeService(store, new PolicyService(store, new RuleBaseService()));
        Map<String, Object> continuation = new LinkedHashMap<>(document("drafted-doc"));
        continuation.put("city_codes", List.of("city-a"));
        assertEquals(
                "drafted-doc",
                knowledge.create("tenant", "author", continuation).data().get("document_id"));
    }

    private static DataSource legacyDatabase() {
        DataSource source =
                PlatformTestDatabase.source(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(source).target("11").load().migrate();
        return source;
    }

    private static void legacyVersion(
            PlatformStore store,
            String id,
            String documentId,
            int version,
            String state,
            String city) {
        Map<String, Object> legacy = new LinkedHashMap<>(document(documentId));
        legacy.put("state", state);
        legacy.put("document_version", version);
        legacy.put("city_codes", List.of(city));
        legacy.put(
                "chunks",
                List.of(
                        Map.of(
                                "chunk_id",
                                id + ":0",
                                "document_id",
                                documentId,
                                "title_path",
                                "legacy",
                                "content",
                                "versionedfact")));
        store.create("tenant", KnowledgeService.KIND, id, legacy, "legacy-author", "T");
    }

    private static PlatformStore.Entity publish(
            KnowledgeService knowledge, PlatformStore.Entity draft) {
        var reviewed =
                knowledge.transition(
                        "tenant", draft.id(), "reviewer", draft.version(), "review", "reviewed");
        return knowledge.transition(
                "tenant", draft.id(), "reviewer", reviewed.version(), "publish", "checked");
    }

    private static Map<String, Object> document(String documentId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "versioned reference");
        request.put("content", "versionedfact");
        if (documentId != null) request.put("document_id", documentId);
        return request;
    }
}
