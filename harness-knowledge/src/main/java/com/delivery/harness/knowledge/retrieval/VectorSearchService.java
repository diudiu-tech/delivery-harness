package com.delivery.harness.knowledge.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class VectorSearchService {

    // MVP: In-memory simple cosine similarity search
    // Production: Replace with Milvus client
    private final Map<String, float[]> vectorStore = new LinkedHashMap<>();
    private final Map<String, String> contentStore = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> metadataStore = new LinkedHashMap<>();

    public void upsert(String id, float[] vector, String content, Map<String, String> metadata) {
        vectorStore.put(id, vector);
        contentStore.put(id, content);
        if (metadata != null) {
            metadataStore.put(id, metadata);
        }
    }

    public List<SearchResult> search(float[] queryVector, int topK) {
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectorStore.entrySet()) {
            double score = cosineSimilarity(queryVector, entry.getValue());
            results.add(SearchResult.builder()
                    .id(entry.getKey())
                    .content(contentStore.get(entry.getKey()))
                    .score(score)
                    .metadata(metadataStore.getOrDefault(entry.getKey(), Collections.emptyMap()))
                    .build());
        }
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());
        return results.subList(0, Math.min(topK, results.size()));
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SearchResult {
        private String id;
        private String content;
        private double score;
        private Map<String, String> metadata;
    }
}
