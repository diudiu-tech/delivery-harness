package com.delivery.harness.cases;

import com.delivery.harness.policy.ComplaintType;

import java.util.List;
import java.util.Optional;

/**
 * Core SPI (2 of 3 per ADR-0004/Rev 2.1) for storing cases and enforcing
 * atomic idempotency, single-open-case uniqueness, and optimistic concurrency.
 */
public interface CaseStore {

    DeliveryCase save(DeliveryCase deliveryCase);

    /**
     * Atomically persists a newly created case after verifying under lock that:
     * <ol>
     *   <li>if {@code idempotencyKey} is already bound, its {@code (orderId, complaintType)}
     *       matches (replaying the existing case) or throws {@code ConflictException}</li>
     *   <li>no open case ({@code status != CLOSED}) already exists for
     *       {@code (orderId, complaintType)}</li>
     * </ol>
     */
    CreateStoreOutcome createAtomically(DeliveryCase candidate);

    /**
     * Atomically updates an existing case only when its stored version equals
     * {@code expectedVersion}, throwing {@code ConflictException} on version
     * mismatch or if reopening would violate single-open-case uniqueness.
     */
    DeliveryCase saveIfVersionMatches(DeliveryCase updated, int expectedVersion);

    Optional<DeliveryCase> findById(String caseId);

    Optional<DeliveryCase> findByIdempotencyKey(String idempotencyKey);

    Optional<DeliveryCase> findOpenByOrderAndComplaint(String orderId, ComplaintType complaintType);

    List<DeliveryCase> listRecent(int limit);

    record CreateStoreOutcome(DeliveryCase deliveryCase, boolean idempotentReplay) {}
}
