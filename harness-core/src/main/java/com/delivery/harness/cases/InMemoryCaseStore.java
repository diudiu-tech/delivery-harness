package com.delivery.harness.cases;

import com.delivery.harness.common.exception.ConflictException;
import com.delivery.harness.common.exception.ResourceNotFoundException;
import com.delivery.harness.policy.ComplaintType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory {@link CaseStore} enforcing atomic idempotency,
 * single-open-case uniqueness, and CAS optimistic concurrency under a single lock.
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
    public synchronized CreateStoreOutcome createAtomically(DeliveryCase candidate) {
        String idemKey = candidate.idempotencyKey();
        if (idemKey != null && !idemKey.isBlank()) {
            String existingId = idempotencyIndex.get(idemKey);
            if (existingId != null) {
                DeliveryCase existing = byId.get(existingId);
                if (existing != null) {
                    validateIdempotencyPayloadMatch(idemKey, existing, candidate.orderId(), candidate.complaintType());
                    return new CreateStoreOutcome(existing, true);
                }
            }
        }

        Optional<DeliveryCase> openDuplicate = findOpenInternal(
                candidate.orderId(), candidate.complaintType(), null);
        if (openDuplicate.isPresent()) {
            throw new ConflictException("An open case already exists for order " + candidate.orderId()
                    + " and complaint " + candidate.complaintType().name() + ": "
                    + openDuplicate.get().caseId());
        }

        save(candidate);
        return new CreateStoreOutcome(candidate, false);
    }

    @Override
    public synchronized DeliveryCase saveIfVersionMatches(DeliveryCase updated, int expectedVersion) {
        DeliveryCase current = byId.get(updated.caseId());
        if (current == null) {
            throw new ResourceNotFoundException("Case not found");
        }
        if (current.version() != expectedVersion) {
            throw new ConflictException("Case version conflict: expected " + expectedVersion
                    + ", current is " + current.version());
        }
        if (current.status() == CaseStatus.CLOSED && updated.status() != CaseStatus.CLOSED) {
            Optional<DeliveryCase> otherOpen = findOpenInternal(
                    updated.orderId(), updated.complaintType(), updated.caseId());
            if (otherOpen.isPresent()) {
                throw new ConflictException("Cannot reopen case while another open case exists for order "
                        + updated.orderId() + " and complaint " + updated.complaintType().name()
                        + ": " + otherOpen.get().caseId());
            }
        }
        byId.put(updated.caseId(), updated);
        return updated;
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
        return findOpenInternal(orderId, complaintType, null);
    }

    @Override
    public synchronized List<DeliveryCase> listRecent(int limit) {
        int safeLimit = Math.max(0, limit);
        return byId.values().stream()
                .sorted(Comparator.comparing(DeliveryCase::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    public static void validateIdempotencyPayloadMatch(
            String idempotencyKey,
            DeliveryCase existing,
            String requestedOrderId,
            ComplaintType requestedComplaintType) {
        if (!existing.orderId().equals(requestedOrderId)
                || existing.complaintType() != requestedComplaintType) {
            throw new ConflictException("Idempotency-Key '" + idempotencyKey
                    + "' was already used with different (order_id, complaint_type)");
        }
    }

    private Optional<DeliveryCase> findOpenInternal(
            String orderId, ComplaintType complaintType, String excludeCaseId) {
        return byId.values().stream()
                .filter(c -> excludeCaseId == null || !c.caseId().equals(excludeCaseId))
                .filter(c -> c.orderId().equals(orderId))
                .filter(c -> c.complaintType() == complaintType)
                .filter(c -> c.status() != CaseStatus.CLOSED)
                .findFirst();
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
