package com.delivery.harness.platform.quality;

import com.delivery.harness.platform.casework.CitationProvider;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.runtime.PersonalDataGuard;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeService implements CitationProvider {
    public static final String KIND = "knowledge";
    public static final String DOCUMENT = "knowledge_document";
    private static final Map<String, String> SYNONYMS =
            Map.of("迟到", "超时", "晚到", "超时", "撒漏", "洒漏", "运力紧张", "运力不足");
    private final PlatformStore store;
    private final PolicyService policies;

    public Map<String, Object> preview(Map<String, Object> request) {
        String content = QualityData.required(request, "content", 100_000);
        String title = QualityData.required(request, "title", 256);
        List<Map<String, Object>> chunks = chunks(content, title);
        List<String> warnings = new ArrayList<>(PersonalDataGuard.findings(title, content));
        if (content.toLowerCase(Locale.ROOT).contains("ignore") || content.contains("忽略规则")) {
            warnings.add("UNTRUSTED_INSTRUCTION_TEXT");
        }
        return Map.of(
                "chunks",
                chunks,
                "content_hash",
                QualityData.hash(content),
                "warnings",
                warnings,
                "source_type",
                QualityData.text(request, "source_type", "document"),
                "trust_level",
                "REFERENCE_ONLY");
    }

    public PlatformStore.Entity create(String tenant, String actor, Map<String, Object> request) {
        Map<String, Object> preview = preview(request);
        Map<String, Object> data = QualityData.copy(request);
        data.putAll(preview);
        data.put("state", "DRAFT");
        data.put("created_by", actor);
        if (request.containsKey("effective_from")) {
            Instant.parse(request.get("effective_from").toString());
        }
        if (request.containsKey("effective_to")) {
            Instant.parse(request.get("effective_to").toString());
        }
        String requestedDocument = QualityData.text(request, "document_id", "");
        String id = UUID.randomUUID().toString();
        return store.transact(
                tx -> {
                    // The locked document head serializes version allocation; a new head is
                    // protected by the record primary key.
                    String documentId;
                    long documentVersion;
                    if (requestedDocument.isBlank()) {
                        documentId = UUID.randomUUID().toString();
                        documentVersion = 1;
                        tx.create(
                                tenant,
                                DOCUMENT,
                                documentId,
                                documentHead(documentId, data, 1, null),
                                actor,
                                "KNOWLEDGE_DOCUMENT_CREATED");
                    } else {
                        PlatformStore.Entity head =
                                tx.getForUpdate(tenant, DOCUMENT, requestedDocument)
                                        .filter(h -> sameScope(h.data(), data))
                                        .orElseThrow(
                                                () ->
                                                        new PlatformException(
                                                                404,
                                                                "NOT_FOUND",
                                                                "Knowledge document not found in"
                                                                        + " authorized scope"));
                        documentId = requestedDocument;
                        documentVersion =
                                Math.addExact(
                                        ((Number) head.data().get("latest_version")).longValue(),
                                        1L);
                        Map<String, Object> next = QualityData.copy(head.data());
                        next.put("latest_version", documentVersion);
                        tx.update(
                                tenant,
                                DOCUMENT,
                                documentId,
                                head.version(),
                                next,
                                actor,
                                "KNOWLEDGE_DOCUMENT_VERSION_ALLOCATED");
                    }
                    data.put("document_id", documentId);
                    data.put("document_version", documentVersion);
                    List<Map<String, Object>> chunks = PolicyService.ruleMaps(data.get("chunks"));
                    chunks.forEach(
                            c -> {
                                c.put("chunk_id", id + ":" + c.get("offset_start"));
                                c.put("version_id", id);
                                c.put("document_id", documentId);
                                c.put("document_version", documentVersion);
                            });
                    data.put("chunks", chunks);
                    return tx.create(tenant, KIND, id, data, actor, "KNOWLEDGE_DRAFT_CREATED");
                });
    }

    /** Version chain head: allocation counter, chain scope and the single current publication. */
    public static Map<String, Object> documentHead(
            String documentId, Map<String, Object> scope, long latest, String current) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("document_id", documentId);
        head.put("latest_version", latest);
        head.put("current_version_id", current);
        head.put("city_codes", QualityData.strings(scope.get("city_codes")));
        head.put("station_ids", QualityData.strings(scope.get("station_ids")));
        return head;
    }

    private static boolean sameScope(Map<String, Object> head, Map<String, Object> data) {
        return Set.copyOf(QualityData.strings(head.get("city_codes")))
                        .equals(Set.copyOf(QualityData.strings(data.get("city_codes"))))
                && Set.copyOf(QualityData.strings(head.get("station_ids")))
                        .equals(Set.copyOf(QualityData.strings(data.get("station_ids"))));
    }

    /** Publication atomically replaces the previous current version of the same document. */
    private void switchCurrentVersion(
            PlatformStore.Tx tx, String tenant, PlatformStore.Entity entity, String actor) {
        String documentId = QualityData.text(entity.data(), "document_id", entity.id());
        long documentVersion =
                entity.data().get("document_version") instanceof Number number
                        ? number.longValue()
                        : 1L;
        PlatformStore.Entity head = tx.getForUpdate(tenant, DOCUMENT, documentId).orElse(null);
        if (head == null) {
            tx.create(
                    tenant,
                    DOCUMENT,
                    documentId,
                    documentHead(documentId, entity.data(), documentVersion, entity.id()),
                    actor,
                    "KNOWLEDGE_DOCUMENT_CREATED");
            return;
        }
        String previousId = QualityData.text(head.data(), "current_version_id", "");
        if (!previousId.isBlank() && !previousId.equals(entity.id())) {
            PlatformStore.Entity previous = tx.get(tenant, KIND, previousId).orElse(null);
            if (previous != null && "PUBLISHED".equals(previous.data().get("state"))) {
                if (previous.data().get("document_version") instanceof Number published
                        && published.longValue() > documentVersion) {
                    throw PlatformException.conflict(
                            "A newer version of this document is already published");
                }
                Map<String, Object> superseded = QualityData.copy(previous.data());
                superseded.put("state", "SUPERSEDED");
                superseded.put("superseded_by", actor);
                superseded.put("superseded_at", Instant.now().toString());
                superseded.put("superseded_by_version", entity.id());
                tx.update(
                        tenant,
                        KIND,
                        previousId,
                        previous.version(),
                        superseded,
                        actor,
                        "KNOWLEDGE_SUPERSEDED");
            }
        }
        Map<String, Object> next = QualityData.copy(head.data());
        next.put("current_version_id", entity.id());
        tx.update(
                tenant,
                DOCUMENT,
                documentId,
                head.version(),
                next,
                actor,
                "KNOWLEDGE_DOCUMENT_CURRENT_CHANGED");
    }

    private void clearCurrentVersion(
            PlatformStore.Tx tx, String tenant, PlatformStore.Entity entity, String actor) {
        String documentId = QualityData.text(entity.data(), "document_id", entity.id());
        PlatformStore.Entity head = tx.getForUpdate(tenant, DOCUMENT, documentId).orElse(null);
        if (head == null || !entity.id().equals(head.data().get("current_version_id"))) {
            return;
        }
        Map<String, Object> next = QualityData.copy(head.data());
        next.put("current_version_id", null);
        tx.update(
                tenant,
                DOCUMENT,
                documentId,
                head.version(),
                next,
                actor,
                "KNOWLEDGE_DOCUMENT_CURRENT_CHANGED");
    }

    public PlatformStore.Entity transition(
            String tenant, String id, String actor, long version, String action, String reason) {
        return store.transact(
                tx -> {
                    PlatformStore.Entity entity = tx.require(tenant, KIND, id);
                    String state = QualityData.text(entity.data(), "state", "");
                    String nextState;
                    switch (action) {
                        case "review" -> {
                            if (!"DRAFT".equals(state)
                                    || actor.equals(entity.data().get("created_by"))) {
                                throw PlatformException.conflict(
                                        "Knowledge needs independent review of draft");
                            }
                            nextState = "REVIEWED";
                        }
                        case "publish" -> {
                            if (!"REVIEWED".equals(state)
                                    || actor.equals(entity.data().get("created_by"))) {
                                throw PlatformException.conflict(
                                        "Only independently reviewed knowledge can be published");
                            }
                            requirePrivacySafe(entity.data());
                            nextState = "PUBLISHED";
                        }
                        case "withdraw" -> {
                            if (reason == null || reason.isBlank()) {
                                throw new IllegalArgumentException("Withdrawal requires a reason");
                            }
                            // Superseded versions can still be withdrawn to stop pinned runtimes.
                            if (!Set.of("PUBLISHED", "SUPERSEDED").contains(state)) {
                                throw new PlatformException(
                                        409,
                                        "KNOWLEDGE_NOT_WITHDRAWABLE",
                                        "Only published or superseded knowledge can be withdrawn");
                            }
                            nextState = "WITHDRAWN";
                        }
                        default -> throw new IllegalArgumentException("Unknown lifecycle action");
                    }
                    if ("PUBLISHED".equals(nextState)) {
                        switchCurrentVersion(tx, tenant, entity, actor);
                    } else if ("WITHDRAWN".equals(nextState)) {
                        clearCurrentVersion(tx, tenant, entity, actor);
                    }
                    Map<String, Object> next = QualityData.copy(entity.data());
                    next.put("state", nextState);
                    next.put(action + "_by", actor);
                    next.put(action + "_at", Instant.now().toString());
                    if (reason != null) {
                        next.put(action + "_reason", reason);
                    }
                    return tx.update(
                            tenant, KIND, id, version, next, actor, "KNOWLEDGE_" + nextState);
                });
    }

    public List<Map<String, Object>> freeze(String tenant, List<String> ids) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            PlatformStore.Entity entity = store.require(tenant, KIND, id);
            // A superseded version stays frozen for runtimes that already pin it; search and
            // citations only treat the current publication as live content.
            if (!Set.of("PUBLISHED", "SUPERSEDED").contains(entity.data().get("state"))) {
                throw new IllegalArgumentException("Knowledge version is not published: " + id);
            }
            requirePrivacySafe(entity.data());
            Map<String, Object> data = QualityData.copy(entity.data());
            // Persist the meaning of an explicitly global document into the immutable runtime
            // snapshot.
            data.putIfAbsent("city_codes", List.of());
            data.putIfAbsent("station_ids", List.of());
            data.put("version_id", id);
            result.add(data);
        }
        return result;
    }

    public Map<String, Object> search(String tenant, Map<String, Object> request) {
        String query = QualityData.required(request, "query", 1000);
        String city = QualityData.text(request, "city_code", "");
        String station = QualityData.text(request, "station_id", "");
        Instant asOf = Instant.parse(QualityData.text(request, "as_of", Instant.now().toString()));
        String scenario = QualityData.text(request, "scenario", "");
        int topK = request.get("top_k") instanceof Number n ? n.intValue() : 10;
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("top_k must be 1..100");
        }
        Set<String> terms = terms(query);
        List<Map<String, Object>> matches = new ArrayList<>();
        int scanLimit =
                request.get("scan_limit") instanceof Number number ? number.intValue() : 200;
        List<PlatformStore.Entity> policyWindow =
                store.query(
                        tenant,
                        PolicyService.KIND,
                        null,
                        Set.of(),
                        "PUBLISHED",
                        QualityData.text(request, "policy_cursor", ""),
                        scanLimit);
        List<PlatformStore.Entity> knowledgeWindow =
                store.query(
                        tenant,
                        KIND,
                        null,
                        Set.of(),
                        "PUBLISHED",
                        QualityData.text(request, "knowledge_cursor", ""),
                        scanLimit);
        for (PlatformStore.Entity policy : policyWindow) {
            if (!"PUBLISHED".equals(policy.data().get("state"))
                    || !PolicyService.applicable(policy.data(), city, station, asOf)) {
                continue;
            }
            for (Map<String, Object> rule : PolicyService.ruleMaps(policy.data().get("rules"))) {
                String type = QualityData.text(rule, "ruleType", "");
                if (Boolean.FALSE.equals(rule.get("enabled"))
                        || (!scenario.isBlank()
                                && !scenario.equals(type)
                                && !("abnormal-order".equals(scenario)
                                        && "analysis".equals(type)))) {
                    continue;
                }
                String ruleId = QualityData.text(rule, "ruleId", "");
                boolean exact = query.equalsIgnoreCase(ruleId);
                String content = QualityData.text(rule, "content", "");
                double score =
                        exact
                                ? 2
                                : relevance(
                                        terms,
                                        ruleId
                                                + " "
                                                + rule.get("ruleName")
                                                + " "
                                                + content
                                                + " "
                                                + rule.get("tags"));
                if (score > 0) {
                    matches.add(
                            citation(
                                    "POLICY",
                                    policy.id() + ":" + ruleId,
                                    policy.id(),
                                    ruleId,
                                    content,
                                    score,
                                    exact));
                }
            }
        }
        for (PlatformStore.Entity document : knowledgeWindow) {
            Map<String, Object> data = document.data();
            if (!"PUBLISHED".equals(data.get("state"))
                    || !PolicyService.applicable(data, city, station, asOf)
                    || (!scenario.isBlank()
                            && data.containsKey("scenario")
                            && !scenario.equals(data.get("scenario")))) {
                continue;
            }
            if (!privacyFindings(data).isEmpty()) {
                continue;
            }
            for (Map<String, Object> chunk : PolicyService.ruleMaps(data.get("chunks"))) {
                double score =
                        relevance(terms, chunk.get("title_path") + " " + chunk.get("content"));
                if (score > 0) {
                    Map<String, Object> found =
                            citation(
                                    "REFERENCE",
                                    chunk.get("chunk_id").toString(),
                                    document.id(),
                                    QualityData.text(data, "title", ""),
                                    chunk.get("content").toString(),
                                    score,
                                    false);
                    found.put("offset_start", chunk.get("offset_start"));
                    found.put("offset_end", chunk.get("offset_end"));
                    found.put("content_hash", chunk.get("content_hash"));
                    matches.add(found);
                }
            }
        }
        matches.sort(
                Comparator.<Map<String, Object>>comparingDouble(
                                m -> ((Number) m.get("score")).doubleValue())
                        .reversed()
                        .thenComparing(m -> m.get("citation_id").toString()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", matches.subList(0, Math.min(matches.size(), topK)));
        result.put("total_found", matches.size());
        result.put("result_scope", "CURRENT_SEARCH_WINDOW");
        result.put("score_meaning", "LEXICAL_RELEVANCE_NOT_PROBABILITY");
        result.put("normalized_terms", terms);
        List<String> diagnostics = new ArrayList<>();
        if (matches.isEmpty()) {
            diagnostics.add("NO_PUBLISHED_MATCH_IN_SCOPE");
        }
        if (policyWindow.size() == scanLimit || knowledgeWindow.size() == scanLimit) {
            diagnostics.add("SEARCH_WINDOW_LIMIT_REACHED");
        }
        result.put("diagnostics", diagnostics);
        result.put(
                "next_policy_cursor",
                policyWindow.size() == scanLimit
                        ? policyWindow.get(policyWindow.size() - 1).id()
                        : null);
        result.put(
                "next_knowledge_cursor",
                knowledgeWindow.size() == scanLimit
                        ? knowledgeWindow.get(knowledgeWindow.size() - 1).id()
                        : null);
        return result;
    }

    public Map<String, Object> citation(String tenant, String citationId) {
        int split = citationId.indexOf(':');
        if (split < 1) {
            throw new IllegalArgumentException("Citation must include version and fragment ID");
        }
        String version = citationId.substring(0, split);
        PlatformStore.Entity document = store.get(tenant, KIND, version).orElse(null);
        if (document != null) {
            for (Map<String, Object> chunk :
                    PolicyService.ruleMaps(document.data().get("chunks"))) {
                if (citationId.equals(chunk.get("chunk_id"))) {
                    Map<String, Object> result = QualityData.copy(chunk);
                    result.put("current_state", document.data().get("state"));
                    result.put("city_codes", document.data().getOrDefault("city_codes", List.of()));
                    result.put(
                            "station_ids", document.data().getOrDefault("station_ids", List.of()));
                    return result;
                }
            }
        }
        PlatformStore.Entity policy = store.require(tenant, PolicyService.KIND, version);
        for (Map<String, Object> rule : PolicyService.ruleMaps(policy.data().get("rules"))) {
            if (citationId.substring(split + 1).equals(rule.get("ruleId"))) {
                Map<String, Object> result = QualityData.copy(rule);
                result.put("current_state", policy.data().get("state"));
                result.put("city_codes", policy.data().getOrDefault("city_codes", List.of()));
                result.put("station_ids", policy.data().getOrDefault("station_ids", List.of()));
                return result;
            }
        }
        throw new IllegalArgumentException("Citation fragment does not exist");
    }

    static List<Map<String, Object>> chunks(String content, String title) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int offset = 0; offset < content.length(); offset += 800) {
            int end = Math.min(content.length(), offset + 800);
            String text = content.substring(offset, end);
            result.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "offset_start",
                                    offset,
                                    "offset_end",
                                    end,
                                    "title_path",
                                    title,
                                    "content",
                                    text,
                                    "content_hash",
                                    QualityData.hash(text))));
        }
        return result;
    }

    public static void requirePrivacySafe(Map<String, Object> data) {
        List<String> findings = privacyFindings(data);
        if (!findings.isEmpty()) {
            throw new PlatformException(
                    422,
                    "KNOWLEDGE_CONTAINS_PERSONAL_DATA",
                    "Remove explicit personal data before publication or runtime use: "
                            + String.join(",", findings));
        }
    }

    private static List<String> privacyFindings(Map<String, Object> data) {
        List<String> texts = new ArrayList<>();
        texts.add(QualityData.text(data, "title", ""));
        texts.add(QualityData.text(data, "content", ""));
        for (Map<String, Object> chunk : PolicyService.ruleMaps(data.get("chunks"))) {
            texts.add(QualityData.text(chunk, "title_path", ""));
            texts.add(QualityData.text(chunk, "content", ""));
        }
        return PersonalDataGuard.findings(texts.toArray(String[]::new));
    }

    static Set<String> terms(String query) {
        String normalized =
                Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> alias : SYNONYMS.entrySet()) {
            normalized = normalized.replace(alias.getKey(), alias.getValue());
        }
        Set<String> result = new LinkedHashSet<>();
        for (String term : normalized.split("[\\s\\p{Punct}，。；：、]+")) {
            if (term.length() > 1) {
                result.add(term);
                if (term.codePoints()
                        .allMatch(
                                c ->
                                        Character.UnicodeScript.of(c)
                                                == Character.UnicodeScript.HAN)) {
                    for (int i = 0; i < term.length() - 1; i++) {
                        result.add(term.substring(i, i + 2));
                    }
                }
            }
        }
        return result;
    }

    static double relevance(Set<String> terms, String text) {
        if (terms.isEmpty()) {
            return 0;
        }
        String haystack = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return (double) terms.stream().filter(haystack::contains).count() / terms.size();
    }

    private static Map<String, Object> citation(
            String source,
            String id,
            String version,
            String title,
            String text,
            double score,
            boolean exact) {
        return new LinkedHashMap<>(
                Map.of(
                        "source_type",
                        source,
                        "citation_id",
                        id,
                        "version_id",
                        version,
                        "title",
                        title,
                        "content",
                        text,
                        "score",
                        score,
                        "exact_id_match",
                        exact));
    }
}
