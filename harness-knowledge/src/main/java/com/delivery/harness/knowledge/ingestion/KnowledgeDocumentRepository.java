package com.delivery.harness.knowledge.ingestion;

import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class KnowledgeDocumentRepository {

    private final Map<String, KnowledgeDocument> store = new ConcurrentHashMap<>();

    public void save(KnowledgeDocument doc) {
        store.put(doc.getDocumentId(), doc);
    }

    public Optional<KnowledgeDocument> findById(String documentId) {
        return Optional.ofNullable(store.get(documentId));
    }

    public List<KnowledgeDocument> findByCategory(String category) {
        return store.values().stream()
                .filter(d -> category.equals(d.getCategory()))
                .collect(Collectors.toList());
    }

    public List<KnowledgeDocument> findAll() {
        return new ArrayList<>(store.values());
    }

    public void deleteById(String documentId) {
        store.remove(documentId);
    }
}
