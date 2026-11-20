package com.delivery.harness.cases;

import com.delivery.harness.policy.ComplaintType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory {@link CaseStore} with zero external dependencies.
 */
@Repository
public class InMemoryCaseStore implements CaseStore {

    private final Map<String, DeliveryCase> byId = new HashMap<>();
    private final Map<String, String> idempotencyIndex = new HashMap<>();
    private final Deque<String> insertionOrder = new ArrayDeque<>();
    private final int maxCases;

    public InMemoryCaseStore(@Value("${harness.cases.max-cases:500}") int maxCases) {
        this.maxCases = Math.max(1, maxCases);
    }

    @Override
    public synchronized DeliveryCase save(DeliveryCase deliveryCase) {
        boolean isNew = !byId.containsKey(deliveryCase.caseId());
        byId.put(deliveryCase.caseId(), deliveryCase);
        if (deliveryCase.idempotencyKey() != null && !deliveryCase.idempotencyKey().isBlank()) {
            idempotencyIndex.put(deliveryCase.idempotencyKey(), deliveryCase.caseId());
        }
        if (isNew) {
            insertionOrder.addLast(deliveryCase.caseId());
            evictIfNeeded();
        }
        return deliveryCase;
    }

    @Override
    public synchronized Optional<DeliveryCase> findById(String caseId) {
        return Optional.ofNullable(byId.get(caseId));
    }

    @Override
    public synchronized Optional<DeliveryCase> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String caseId = idempotencyIndex.get(idempotencyKey);
        return caseId == null ? Optional.empty() : Optional.ofNullable(byId.get(caseId));
    }

    @Override
    public synchronized Optional<DeliveryCase> findOpenByOrderAndComplaint(
            String orderId, ComplaintType complaintType) {
        return byId.values().stream()
                .filter(c -> c.orderId().equals(orderId))
                .filter(c -> c.complaintType() == complaintType)
                .filter(c -> c.status() != CaseStatus.CLOSED)
                .findFirst();
    }

    @Override
    public synchronized List<DeliveryCase> listRecent(int limit) {
        int safeLimit = Math.max(0, limit);
        return byId.values().stream()
                .sorted(Comparator.comparing(DeliveryCase::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    private void evictIfNeeded() {
        while (insertionOrder.size() > maxCases) {
            String oldestId = insertionOrder.pollFirst();
            if (oldestId != null) {
                DeliveryCase removed = byId.remove(oldestId);
                if (removed != null && removed.idempotencyKey() != null) {
                    idempotencyIndex.remove(removed.idempotencyKey());
                }
            }
        }
    }
}
