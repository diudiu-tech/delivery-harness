package com.delivery.harness.knowledge.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Repository
public class KnowledgeDocumentRepository {

    private final Map<String, KnowledgeDocument> store = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> documentOrder = new ConcurrentLinkedDeque<>();

    @Value("${harness.knowledge.max-documents:1000}")
    private int maxDocuments = 1000;

    public synchronized void save(KnowledgeDocument doc) {
        store.put(doc.getDocumentId(), doc);
        documentOrder.remove(doc.getDocumentId());
        documentOrder.addLast(doc.getDocumentId());
        while (documentOrder.size() > Math.max(1, maxDocuments)) {
            String oldest = documentOrder.pollFirst();
            if (oldest != null) {
                store.remove(oldest);
            }
        }
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

    public synchronized void deleteById(String documentId) {
        store.remove(documentId);
        documentOrder.remove(documentId);
    }
}
