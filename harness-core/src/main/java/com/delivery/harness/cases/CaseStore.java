package com.delivery.harness.cases;

import com.delivery.harness.policy.ComplaintType;

import java.util.List;
import java.util.Optional;

/**
 * Core SPI (2 of 3 per ADR-0004/Rev 2.1) for storing cases and enforcing
 * idempotency / single-open-case lookup.
 */
public interface CaseStore {

    DeliveryCase save(DeliveryCase deliveryCase);

    Optional<DeliveryCase> findById(String caseId);

    Optional<DeliveryCase> findByIdempotencyKey(String idempotencyKey);

    Optional<DeliveryCase> findOpenByOrderAndComplaint(String orderId, ComplaintType complaintType);

    List<DeliveryCase> listRecent(int limit);
}
