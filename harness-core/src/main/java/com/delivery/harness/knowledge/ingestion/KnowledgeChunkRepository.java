package com.delivery.harness.knowledge.ingestion;

import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class KnowledgeChunkRepository {

    private final Map<String, KnowledgeChunk> store = new ConcurrentHashMap<>();

    public void save(KnowledgeChunk chunk) {
        store.put(chunk.getChunkId(), chunk);
    }

    public void saveAll(List<KnowledgeChunk> chunks) {
        chunks.forEach(this::save);
    }

    public Optional<KnowledgeChunk> findById(String chunkId) {
        return Optional.ofNullable(store.get(chunkId));
    }

    public List<KnowledgeChunk> findByDocumentId(String documentId) {
        return store.values().stream()
                .filter(c -> documentId.equals(c.getDocumentId()))
                .sorted(Comparator.comparingInt(KnowledgeChunk::getChunkIndex))
                .collect(Collectors.toList());
    }

    public List<KnowledgeChunk> findAll() {
        return new ArrayList<>(store.values());
    }
}
