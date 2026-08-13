package com.delivery.harness.knowledge.retrieval;

import com.delivery.harness.knowledge.casemgmt.CaseBaseService;
import com.delivery.harness.knowledge.ingestion.KnowledgeChunk;
import com.delivery.harness.knowledge.ingestion.KnowledgeChunkRepository;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.common.dto.CaseInfo;
import com.delivery.harness.common.dto.RuleInfo;
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

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final VectorSearchService vectorSearchService;
    private final RuleBaseService ruleBaseService;
    private final CaseBaseService caseBaseService;
    private final KnowledgeChunkRepository chunkRepository;

    public RetrievalResult retrieve(RetrievalRequest request) {
        List<RetrievalItem> items = new ArrayList<>();

        // 1. Keyword search in rules
        if (request.getSourceTypes() == null || request.getSourceTypes().contains("rule")) {
            List<RuleInfo> rules = ruleBaseService.searchByKeyword(request.getQuery());
            for (RuleInfo rule : rules) {
                items.add(RetrievalItem.builder()
                        .sourceType("rule")
                        .sourceId(rule.getRuleId())
                        .content(rule.getContent())
                        .title(rule.getRuleName())
                        .score(0.8)
                        .build());
            }
        }

        // 2. Keyword search in chunks (simple contains match for MVP)
        if (request.getSourceTypes() == null || request.getSourceTypes().contains("document")) {
            String lowerQuery = request.getQuery().toLowerCase();
            List<KnowledgeChunk> matchedChunks = chunkRepository.findAll().stream()
                    .filter(c -> c.getContent().toLowerCase().contains(lowerQuery))
                    .limit(request.getTopK() != null ? request.getTopK() : 5)
                    .collect(Collectors.toList());
            for (KnowledgeChunk chunk : matchedChunks) {
                items.add(RetrievalItem.builder()
                        .sourceType("document")
                        .sourceId(chunk.getDocumentId())
                        .content(chunk.getContent())
                        .title("chunk-" + chunk.getChunkIndex())
                        .score(0.6)
                        .build());
            }
        }

        // 3. Case search by tags
        if (request.getSourceTypes() == null || request.getSourceTypes().contains("case")) {
            List<CaseInfo> cases = caseBaseService.findByScenario(
                    request.getScenario() != null ? request.getScenario() : "");
            for (CaseInfo c : cases) {
                items.add(RetrievalItem.builder()
                        .sourceType("case")
                        .sourceId(c.getCaseId())
                        .content(c.getSummary())
                        .title(c.getTitle())
                        .score(0.5)
                        .build());
            }
        }

        // Sort by score descending and limit
        int topK = request.getTopK() != null ? request.getTopK() : 10;
        items.sort(Comparator.comparingDouble(RetrievalItem::getScore).reversed());
        items = items.subList(0, Math.min(topK, items.size()));

        return RetrievalResult.builder()
                .query(request.getQuery())
                .items(items)
                .totalFound(items.size())
                .build();
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
