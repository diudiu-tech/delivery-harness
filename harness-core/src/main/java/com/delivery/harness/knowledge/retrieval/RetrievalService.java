package com.delivery.harness.knowledge.retrieval;

import com.delivery.harness.common.dto.CaseInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.knowledge.casemgmt.CaseBaseService;
import com.delivery.harness.knowledge.ingestion.KnowledgeChunk;
import com.delivery.harness.knowledge.ingestion.KnowledgeChunkRepository;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lexical retrieval over the in-memory rule, document and case stores.
 *
 * <p>Scoring is token overlap: the query is split into terms and an item's
 * score is the fraction of terms it contains, with a small weight per source
 * type to break ties. It is not semantic search, and it is not pretending to
 * be — {@code VectorSearchService} was removed precisely because it implied
 * otherwise.
 *
 * <p>What this replaces mattered more than what it is. Previously every rule
 * scored a constant 0.8, every document 0.6 and every case 0.5, so sorting by
 * score only ever produced "rules, then documents, then cases" and the score
 * on a citation carried no information. Rules were matched by testing whether
 * the entire query string appeared inside the rule text, which fails for any
 * multi-term query; cases ignored the query completely and were returned by
 * scenario alone.
 *
 * <p>At this corpus size — a policy set of roughly a dozen rules — token
 * overlap is sufficient and embeddings would be unjustifiable. See
 * {@code docs/adr/0003-no-vector-retrieval-at-this-corpus-size.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final Pattern TERM_SEPARATOR = Pattern.compile("[\\s,，、;；/|]+");
    private static final int DEFAULT_TOP_K = 10;

    /** Tie-breakers only; they never let an unmatched item outrank a matched one. */
    private static final double RULE_WEIGHT = 1.00;
    private static final double CASE_WEIGHT = 0.95;
    private static final double DOCUMENT_WEIGHT = 0.90;

    private final RuleBaseService ruleBaseService;
    private final CaseBaseService caseBaseService;
    private final KnowledgeChunkRepository chunkRepository;

    public RetrievalResult retrieve(RetrievalRequest request) {
        Set<String> terms = tokenize(request.getQuery());
        int topK = request.getTopK() != null ? request.getTopK() : DEFAULT_TOP_K;
        List<RetrievalItem> items = new ArrayList<>();

        if (wants(request, "rule")) {
            for (RuleInfo rule : ruleBaseService.findAll()) {
                if (!Boolean.FALSE.equals(rule.getEnabled())) {
                    double overlap = overlap(terms, rule.getRuleName(), rule.getContent(),
                            rule.getCategory(), joinTags(rule.getTags()));
                    if (overlap > 0) {
                        items.add(RetrievalItem.builder()
                                .sourceType("rule")
                                .sourceId(rule.getRuleId())
                                .title(rule.getRuleName())
                                .content(rule.getContent())
                                .score(round(overlap * RULE_WEIGHT))
                                .build());
                    }
                }
            }
        }

        if (wants(request, "case")) {
            for (CaseInfo caseInfo : casesInScope(request)) {
                double overlap = overlap(terms, caseInfo.getTitle(), caseInfo.getSummary(),
                        caseInfo.getExpertAnswer(), joinTags(caseInfo.getTags()));
                if (overlap > 0) {
                    items.add(RetrievalItem.builder()
                            .sourceType("case")
                            .sourceId(caseInfo.getCaseId())
                            .title(caseInfo.getTitle())
                            .content(caseInfo.getSummary())
                            .score(round(overlap * CASE_WEIGHT))
                            .build());
                }
            }
        }

        if (wants(request, "document")) {
            for (KnowledgeChunk chunk : chunkRepository.findAll()) {
                double overlap = overlap(terms, chunk.getContent());
                if (overlap > 0) {
                    items.add(RetrievalItem.builder()
                            .sourceType("document")
                            .sourceId(chunk.getDocumentId())
                            .title("chunk-" + chunk.getChunkIndex())
                            .content(chunk.getContent())
                            .score(round(overlap * DOCUMENT_WEIGHT))
                            .build());
                }
            }
        }

        items.sort(Comparator.comparingDouble(RetrievalItem::getScore).reversed()
                .thenComparing(RetrievalItem::getSourceId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<RetrievalItem> top = items.subList(0, Math.min(topK, items.size()));

        log.debug("Retrieval: query='{}', terms={}, candidates={}, returned={}",
                request.getQuery(), terms.size(), items.size(), top.size());

        return RetrievalResult.builder()
                .query(request.getQuery())
                .items(new ArrayList<>(top))
                .totalFound(top.size())
                .build();
    }

    /**
     * Cases filtered by scenario when one is supplied. A case recorded against
     * a different scenario is not evidence for this one, so this is a filter
     * rather than a scoring signal.
     */
    private List<CaseInfo> casesInScope(RetrievalRequest request) {
        if (request.getScenario() == null || request.getScenario().isBlank()) {
            return caseBaseService.findAll();
        }
        return caseBaseService.findByScenario(request.getScenario());
    }

    private static boolean wants(RetrievalRequest request, String sourceType) {
        return request.getSourceTypes() == null
                || request.getSourceTypes().isEmpty()
                || request.getSourceTypes().contains(sourceType);
    }

    /** Splits a query into lowercase terms, dropping single characters. */
    static Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(TERM_SEPARATOR.split(query.toLowerCase()))
                .map(String::trim)
                .filter(term -> term.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Fraction of query terms appearing in any of the supplied fields. */
    static double overlap(Set<String> terms, String... fields) {
        if (terms.isEmpty()) {
            return 0d;
        }
        StringBuilder haystack = new StringBuilder();
        for (String field : fields) {
            if (field != null) {
                haystack.append(field.toLowerCase()).append('\n');
            }
        }
        String text = haystack.toString();
        long matched = terms.stream().filter(text::contains).count();
        return (double) matched / terms.size();
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static String joinTags(List<String> tags) {
        return tags == null ? null : String.join(" ", tags);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalRequest {
        @NotBlank
        @Size(max = 1_000)
        private String query;

        @Size(max = 100)
        private String scenario;

        @Size(max = 3)
        private List<String> sourceTypes;

        @Min(1)
        @Max(100)
        private Integer topK;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalResult {
        private String query;
        private List<RetrievalItem> items;
        private int totalFound;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalItem {
        private String sourceType;
        private String sourceId;
        private String title;
        private String content;
        private double score;
    }
}
